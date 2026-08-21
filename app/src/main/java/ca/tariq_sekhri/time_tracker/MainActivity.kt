package ca.tariq_sekhri.time_tracker

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import kotlin.math.max

/** Figma-led mobile shell. Notes remains its own activity and data stays in DatabaseHelper. */
class MainActivity : AppCompatActivity() {
    private val db by lazy { DatabaseHelper(this) }
    private val syncManager by lazy { SyncManager(this) }
    private lateinit var screen: FrameLayout
    private lateinit var nav: LinearLayout
    private var activeScroll: ScrollView? = null
    private var liveLogList: ListView? = null
    private var usageAccessDialog: AlertDialog? = null
    private var isImportingUsage = false
    private val timelineRefreshHandler = Handler(Looper.getMainLooper())
    private val backgroundImportHandler = Handler(Looper.getMainLooper())
    private val syncRefreshHandler = Handler(Looper.getMainLooper())
    private var syncCountdownText: TextView? = null
    private var syncOtherDevices: List<SyncManager.ServerDevice>? = null
    private val syncRefresh = object : Runnable { override fun run() { if(current!=Page.SYNC || isFinishing) return; syncCountdownText?.text=formatSyncCountdown(syncManager.secondsUntilNextAutoPush()); syncRefreshHandler.postDelayed(this,1_000L) } }
    private val timelineRefresh = object : Runnable {
        override fun run() {
            if (current != Page.TIMELINE || isFinishing) return
            importUsageForLiveTable()
            timelineRefreshHandler.postDelayed(this, 1_000L)
        }
    }
    private val backgroundImport = object : Runnable {
        override fun run() {
            if (!isFinishing && current != Page.TIMELINE) importUsageForCurrentScreen()
            backgroundImportHandler.postDelayed(this, 60_000L)
        }
    }
    private var current = Page.OVERVIEW
    private var usagePeriod = "Today"
    private var showAllHomeApps = false
    private var usageBrowserOnly = false
    private var customRangeStartMs: Long? = null
    private var customRangeEndMs: Long? = null
    private var liveLogDayStartMs: Long? = null
    private var calendarDayStartMs: Long? = null
    private var calendarDayZoom=1f
    private var calendarDayPanHours=0f
    private var statisticsRangeStartMs: Long? = null
    private var statisticsRangeEndMs: Long? = null
    private var showAllStatisticsApps=false
    private var isChangingSyncServer=false
    private enum class Page { OVERVIEW, USAGE_CHART, TIMELINE, CALENDAR, BROWSER, DAILY, TOTAL, SYNC, NOTES, SETTINGS }
    private val bg = Color.rgb(17, 19, 24); private val panel = Color.rgb(28, 32, 42)
    private val navy = Color.rgb(22, 32, 52); private val purple = Color.rgb(177, 139, 255); private val muted = Color.rgb(157, 164, 178)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = when (intent.getStringExtra(EXTRA_START_PAGE)) {
            "timeline" -> Page.TIMELINE
            "browser" -> Page.BROWSER
            "settings" -> Page.SETTINGS
            else -> Page.OVERVIEW
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        screen = FrameLayout(this); root.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(12)); setBackgroundColor(Color.rgb(14, 16, 21)) }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(68))); setContentView(root); render(current)
        screen.post { requireUsageAccess() }
    }
    override fun onResume() {
        super.onResume()
        if (::screen.isInitialized) render(current)
        screen.post {
            requireUsageAccess()
            if (UsageLogImporter.hasUsageAccess(this)) importUsageSilently()
            backgroundImportHandler.removeCallbacks(backgroundImport)
            backgroundImportHandler.postDelayed(backgroundImport, 60_000L)
        }
    }
    override fun onPause() {
        timelineRefreshHandler.removeCallbacks(timelineRefresh)
        backgroundImportHandler.removeCallbacks(backgroundImport)
        syncRefreshHandler.removeCallbacks(syncRefresh)
        super.onPause()
    }
    private fun render(page: Page) {
        timelineRefreshHandler.removeCallbacks(timelineRefresh)
        syncRefreshHandler.removeCallbacks(syncRefresh)
        current = page; screen.removeAllViews(); nav.removeAllViews()
        if (page == Page.TIMELINE) {
            activeScroll = null
            screen.addView(legacyLiveTimeline())
        } else if (page == Page.BROWSER) {
            liveLogList = null
            activeScroll = ScrollView(this).apply {
                isFillViewport = true; clipToPadding = false; setPadding(0,0,0,dp(66)); setBackgroundColor(bg)
                addView(calendarDayScreen())
            }
            screen.addView(activeScroll, FrameLayout.LayoutParams(-1,-1))
            screen.addView(calendarDayPicker(), FrameLayout.LayoutParams(-1,dp(66),Gravity.BOTTOM))
        } else if (page == Page.NOTES) {
            activeScroll = null
            screen.addView(notesView())
        } else {
            liveLogList = null
            activeScroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(bg); addView(when (page) { Page.OVERVIEW -> overview(); Page.USAGE_CHART -> usageChart(); Page.TIMELINE -> error("handled above"); Page.CALENDAR -> timeline(true); Page.BROWSER -> error("handled above"); Page.DAILY -> statistics(false); Page.TOTAL -> statistics(true); Page.SYNC -> syncView(); Page.NOTES -> error("handled above"); Page.SETTINGS -> settings() }) }
            screen.addView(activeScroll)
        }
        buildList { add(R.drawable.ic_nav_dashboard to Page.OVERVIEW); add(R.drawable.ic_nav_logs to Page.TIMELINE); add(R.drawable.ic_nav_calendar to Page.BROWSER); add(R.drawable.ic_nav_statistics to Page.DAILY); add(R.drawable.ic_nav_sync to Page.SYNC); if(notesEnabled()) add(R.drawable.ic_nav_notes to Page.NOTES); add(R.drawable.ic_nav_settings to Page.SETTINGS) }.forEach { (icon, target) -> nav.addView(navIcon(icon, target == page || (target == Page.OVERVIEW && page == Page.USAGE_CHART) || (target == Page.TIMELINE && page == Page.CALENDAR)).apply { layoutParams = LinearLayout.LayoutParams(0, -1, 1f); setOnClickListener { render(target) } }) }
        if (page == Page.TIMELINE) timelineRefreshHandler.postDelayed(timelineRefresh, 1_000L)
        if (page == Page.SYNC) syncRefreshHandler.postDelayed(syncRefresh, 1_000L)
    }
    private fun base() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
    private fun title(text: String) = TextView(this).apply { this.text = text; setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
    private fun label(text: String, size: Float = 13f) = TextView(this).apply { this.text = text; setTextColor(muted); textSize = size }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(panel); setPadding(dp(14), dp(14), dp(14), dp(14)) }
    private fun gap(parent: LinearLayout, h: Int = 12) = parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(h)))
    private fun button(text: String, selected: Boolean = false, size: Int = 14) = Button(this).apply { this.text = text; textSize = size.toFloat(); transformationMethod = null; setTextColor(Color.WHITE); setBackgroundColor(if (selected) Color.rgb(101, 70, 164) else Color.rgb(42, 47, 60)); minHeight = 0; minimumHeight = 0; setPadding(dp(6), 0, dp(6), 0) }
    private fun navIcon(icon:Int,selected:Boolean)=ImageButton(this).apply { setImageResource(icon); setColorFilter(Color.WHITE); setBackgroundColor(if(selected) Color.rgb(101,70,164) else Color.TRANSPARENT); setPadding(dp(10),dp(8),dp(10),dp(8)); contentDescription="Navigation" }
    private fun periodTabs(selected: String, onSelect: (String) -> Unit = {}) = LinearLayout(this).apply { setBackgroundColor(Color.rgb(232,232,237)); setPadding(dp(3),dp(3),dp(3),dp(3)); listOf("Today","Week","Month").forEach { p -> addView(Button(this@MainActivity).apply { text=p; textSize=14f; transformationMethod=null; setTextColor(Color.rgb(27,29,35)); setBackgroundColor(if(p==selected) Color.rgb(216,198,255) else Color.TRANSPARENT); setOnClickListener { onSelect(p) } }, LinearLayout.LayoutParams(0,dp(48),1f)) } }
    private fun usagePeriodSelector(onSelect: (String) -> Unit) = LinearLayout(this).apply {
        addView(periodTabs(if(usagePeriod=="Custom") "" else usagePeriod,onSelect),LinearLayout.LayoutParams(0,dp(54),1f))
        addView(button("▣",usagePeriod=="Custom",18).apply { contentDescription="Choose date range"; setOnClickListener { showCustomRangePicker() } },LinearLayout.LayoutParams(dp(52),dp(54)).apply { marginStart=dp(6) })
    }

    private fun overview(): View {
        val root=base(); root.addView(title("Usage")); gap(root,10); root.addView(usagePeriodSelector { usagePeriod=it; showAllHomeApps=false; render(Page.OVERVIEW) }); gap(root)
        if (!hasAllTrackingPermissions()) {
            root.addView(permissionCard()); gap(root)
        }
        val logs=usageLogs(); val total=logs.sumOf { it.duration }
        val allAppSlices=usageAppSlices(logs)
        val donutSlices=allAppSlices.take(8)
        root.addView(card().apply {
            val cardHeader=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL }
            cardHeader.addView(title("Mobile App Usage Breakdown").apply { textSize=17f },LinearLayout.LayoutParams(0,-2,1f))
            val modeSwitch=LinearLayout(this@MainActivity).apply { setBackgroundColor(Color.rgb(42,47,60)); setPadding(dp(2),dp(2),dp(2),dp(2)) }
            modeSwitch.addView(button("◔",true,20).apply { setOnClickListener { render(Page.OVERVIEW) } },LinearLayout.LayoutParams(dp(42),dp(38)))
            modeSwitch.addView(button("⌁",false,20).apply { setOnClickListener { render(Page.USAGE_CHART) } },LinearLayout.LayoutParams(dp(42),dp(38)))
            cardHeader.addView(modeSwitch)
            addView(cardHeader)
            if (donutSlices.isEmpty()) addView(label("No usage data in this selected range.",15f).apply { gravity=Gravity.CENTER; setPadding(0,dp(80),0,dp(80)) })
            else addView(AppUsageDonutView(this@MainActivity,donutSlices),LinearLayout.LayoutParams(-1,dp(270)))
            addView(label("━━━━━━",18f).apply { gravity=Gravity.CENTER; setTextColor(purple) })
        }); gap(root)
        root.addView(card().apply {
            val listHeader=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL }
            listHeader.addView(title("Apps").apply { textSize=17f },LinearLayout.LayoutParams(0,-2,1f))
            listHeader.addView(button("Browser",usageBrowserOnly,13).apply { setOnClickListener { usageBrowserOnly=!usageBrowserOnly; render(Page.OVERVIEW) } },LinearLayout.LayoutParams(dp(88),dp(38)))
            addView(listHeader)
            allAppSlices.take(if(showAllHomeApps) allAppSlices.size else 5).forEach { addUsageRow(this,it.label,it.seconds,total,it.color) }
            if (!showAllHomeApps && allAppSlices.size > 5) addView(button("Show more apps (${allAppSlices.size - 5})").apply { setOnClickListener { showAllHomeApps=true; render(Page.OVERVIEW) } },LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin=dp(8) })
        }); gap(root)
        return root
    }
    private fun usageAppSlices(logs: List<LogEntry>): List<UsageDonutSlice> {
        val groups=if (usageBrowserOnly) {
            // Browser mode intentionally preserves the title/domain recorded by BrowserTrackerService.
            logs.groupBy { entry -> entry.packageName to (entry.appLabel?.takeIf { it.isNotBlank() } ?: genericAppLabel(entry.packageName)) }
        } else {
            // Normal app usage never exposes a browsing title; Chrome and Vivaldi stay generic apps.
            logs.groupBy { entry -> entry.packageName to genericAppLabel(entry.packageName) }
        }
        return groups.map { (key, entries) ->
            val icon=if (usageBrowserOnly) null else runCatching { packageManager.getApplicationIcon(key.first) }.getOrNull()
            val color=if (usageBrowserOnly) browserDetailColor(key.second) else AppUsageDonutView.colorFromIcon(icon)
            UsageDonutSlice(key.first,key.second,entries.sumOf { it.duration },icon,color)
        }.filter { it.seconds>=minAppDuration() }.sortedByDescending { it.seconds }
    }
    private fun browserDetailColor(label: String): Int {
        val palette=intArrayOf(
            Color.rgb(177,139,255), Color.rgb(76,142,236), Color.rgb(67,184,170),
            Color.rgb(244,174,76), Color.rgb(232,99,139), Color.rgb(128,190,92),
            Color.rgb(239,126,78), Color.rgb(102,161,221)
        )
        return palette[(label.hashCode() and Int.MAX_VALUE) % palette.size]
    }
    private fun genericAppLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName,0)).toString()
    }.getOrElse {
        when(packageName) { "com.android.chrome" -> "Chrome"; "com.vivaldi.browser" -> "Vivaldi"; else -> packageName }
    }
    private fun addUsageRow(parent: LinearLayout,name:String,seconds:Long,total:Long,color:Int) { val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(9),0,dp(9)) }; val head=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }; head.addView(TextView(this).apply { text="●  $name"; setTextColor(Color.WHITE); textSize=15f },LinearLayout.LayoutParams(0,-2,1f)); head.addView(label("${formatDuration(seconds)}\n${if(total==0L)0 else seconds*100/total}%").apply { gravity=Gravity.END }); row.addView(head); row.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=max(1,total.toInt()); progress=seconds.toInt(); progressTintList=android.content.res.ColorStateList.valueOf(color) },LinearLayout.LayoutParams(-1,dp(6)).apply { topMargin=dp(5) }); parent.addView(row) }

    private fun legacyLiveTimeline(): View {
        val shell=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(12),dp(12),dp(66)) }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(31,41,55)); setPadding(dp(8),dp(8),dp(8),dp(8)) }
        header.addView(button("Skipped").apply { setOnClickListener { startActivity(Intent(this@MainActivity,SkippedAppsActivity::class.java)) } },LinearLayout.LayoutParams(-1,dp(46)))
        root.addView(header)
        liveLogList=ListView(this).apply { divider=null; dividerHeight=0; setBackgroundColor(Color.BLACK); clipToPadding=false; setPadding(0,dp(8),0,0) }
        root.addView(liveLogList,LinearLayout.LayoutParams(-1,0,1f))
        shell.addView(root,FrameLayout.LayoutParams(-1,-1))
        shell.addView(liveLogDayPicker(),FrameLayout.LayoutParams(-1,dp(66),Gravity.BOTTOM))
        refreshLiveLogTable()
        return shell
    }
    private fun liveLogDayPicker() = LinearLayout(this).apply {
        gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(14,16,21)); setPadding(dp(3),dp(6),dp(3),dp(6))
        val selected=liveLogDayStart(); val first=firstLiveLogDay(); val today=trackingDayStart(System.currentTimeMillis())
        (-2..2).forEach { offset ->
            val day=selected+offset*86_400_000L
            val enabled=day in first..today
            val text=SimpleDateFormat("EEE\nd",Locale.getDefault()).format(Date(day))
            addView(Button(this@MainActivity).apply {
                this.text=text; textSize=11f; transformationMethod=null; setTextColor(if(enabled) Color.rgb(210,213,220) else Color.rgb(78,82,91)); setBackgroundColor(if(day==selected) Color.rgb(42,47,60) else Color.TRANSPARENT); isEnabled=enabled
                if(enabled) setOnClickListener { liveLogDayStartMs=day; render(Page.TIMELINE) }
            },LinearLayout.LayoutParams(0,dp(52),1f))
        }
        addView(button("↺",false,22).apply { contentDescription="Return to today"; isEnabled=selected!=today; setOnClickListener { liveLogDayStartMs=today; render(Page.TIMELINE) } },LinearLayout.LayoutParams(dp(48),dp(52)).apply { marginStart=dp(5) })
        addView(button("▣",false,20).apply { contentDescription="Choose log date"; setOnClickListener { showLiveLogDatePicker() } },LinearLayout.LayoutParams(dp(48),dp(52)).apply { marginStart=dp(5) })
    }
    private fun firstLiveLogDay() = db.getAllLogs().minOfOrNull { trackingDayStart(it.startTimestamp) } ?: trackingDayStart(System.currentTimeMillis())
    private fun liveLogDayStart(): Long = liveLogDayStartMs ?: trackingDayStart(System.currentTimeMillis()).also { liveLogDayStartMs=it }
    private fun trackingDayStart(timestamp:Long):Long = Calendar.getInstance().apply { timeInMillis=timestamp; set(Calendar.HOUR_OF_DAY,dayStartHour()); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0); if(timestamp<timeInMillis) add(Calendar.DAY_OF_YEAR,-1) }.timeInMillis
    private fun showLiveLogDatePicker() {
        val first=firstLiveLogDay(); val today=trackingDayStart(System.currentTimeMillis()); val initial=Calendar.getInstance().apply { timeInMillis=liveLogDayStart() }
        DatePickerDialog(this,{ _,year,month,day ->
            liveLogDayStartMs=Calendar.getInstance().apply { set(year,month,day,dayStartHour(),0,0); set(Calendar.MILLISECOND,0) }.timeInMillis
            render(Page.TIMELINE)
        },initial.get(Calendar.YEAR),initial.get(Calendar.MONTH),initial.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate=first; datePicker.maxDate=today }.show()
    }

    private fun notesView(): View {
        val prefs=getSharedPreferences("TimeTrackerNotes",MODE_PRIVATE)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(31,41,55)); setPadding(dp(16),dp(8),dp(12),dp(8)) }
        header.addView(title("Notes"),LinearLayout.LayoutParams(0,dp(52),1f))
        val editor=EditText(this).apply {
            hint="Write your notes here..."; setHintTextColor(Color.rgb(107,114,128)); setTextColor(Color.WHITE); textSize=16f; gravity=Gravity.TOP or Gravity.START
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; background=null; setPadding(dp(16),dp(16),dp(16),dp(16)); setText(prefs.getString("notes_content","")); setSelection(text.length)
            addTextChangedListener(object: TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}; override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}; override fun afterTextChanged(s:Editable?){ prefs.edit().putString("notes_content",s?.toString() ?: "").apply() } })
        }
        header.addView(button("Copy").apply { setOnClickListener { copy(editor.text.toString()) } },LinearLayout.LayoutParams(dp(64),dp(42)))
        header.addView(button("Clear").apply { setOnClickListener { if(editor.text.isNotBlank()) AlertDialog.Builder(this@MainActivity).setTitle("Clear notes?").setMessage("Are you sure you want to clear your notes?").setNegativeButton("Cancel",null).setPositiveButton("Clear") { _,_-> editor.setText("") }.show() } },LinearLayout.LayoutParams(dp(64),dp(42)).apply { marginStart=dp(6) })
        root.addView(header)
        root.addView(ScrollView(this).apply { isFillViewport=true; addView(editor) },LinearLayout.LayoutParams(-1,0,1f))
        return root
    }

    private fun refreshLiveLogTable() {
        val list=liveLogList ?: return
        val first=list.firstVisiblePosition
        val top=list.getChildAt(0)?.top ?: 0
        val dayStart=liveLogDayStart(); val dayEnd=dayStart+86_400_000L
        val logs=db.getAllLogs().filter { it.startTimestamp in dayStart until dayEnd }
        list.adapter=object : ArrayAdapter<LogEntry>(this,0,logs) {
            override fun getView(position:Int, convertView:View?, parent:ViewGroup):View {
                val entry=getItem(position)!!
                val row=RelativeLayout(context).apply { layoutParams=AbsListView.LayoutParams(-1,-2); setBackgroundColor(if(position%2==0)Color.BLACK else Color.rgb(17,24,39)); setPadding(dp(16),dp(12),dp(8),dp(12)) }
                val delete=ImageView(context).apply { setImageResource(android.R.drawable.ic_menu_delete); setColorFilter(Color.rgb(153,27,27)); setPadding(dp(8),dp(8),dp(8),dp(8)); id=View.generateViewId(); contentDescription="Delete log"; layoutParams=RelativeLayout.LayoutParams(dp(40),dp(40)).apply { addRule(RelativeLayout.ALIGN_PARENT_END); addRule(RelativeLayout.CENTER_VERTICAL) }; setOnClickListener { db.deleteLog(entry.id); refreshLiveLogTable() } }
                val copy=ImageView(context).apply { setImageResource(R.drawable.ic_copy); setColorFilter(Color.rgb(156,163,175)); setPadding(dp(8),dp(8),dp(8),dp(8)); id=View.generateViewId(); contentDescription="Copy log"; layoutParams=RelativeLayout.LayoutParams(dp(40),dp(40)).apply { addRule(RelativeLayout.START_OF,delete.id); addRule(RelativeLayout.CENTER_VERTICAL) }; setOnClickListener { copy(entry.packageName) } }
                val skip=ImageView(context).apply { setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setColorFilter(Color.rgb(245,158,11)); setPadding(dp(8),dp(8),dp(8),dp(8)); id=View.generateViewId(); contentDescription="Add to skipped apps"; layoutParams=RelativeLayout.LayoutParams(dp(40),dp(40)).apply { addRule(RelativeLayout.START_OF,copy.id); addRule(RelativeLayout.CENTER_VERTICAL) }; setOnClickListener { confirmSkip(entry.packageName) } }
                val text=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; layoutParams=RelativeLayout.LayoutParams(-1,-2).apply { addRule(RelativeLayout.START_OF,skip.id) }; addView(TextView(context).apply { this.text=entry.appLabel ?: entry.packageName; setTextColor(Color.rgb(209,213,219)); textSize=14f; typeface=Typeface.DEFAULT_BOLD }); addView(label("${timestamp(entry.startTimestamp)}\n${formatDuration(entry.duration)}")) }
                row.addView(text); row.addView(skip); row.addView(copy); row.addView(delete); return row
            }
        }
        if (first >= 0) list.setSelectionFromTop(first,top)
    }

    private fun confirmSkip(pattern:String) {
        val count=db.countMatchingLogs(pattern)
        AlertDialog.Builder(this).setTitle("Add to skipped apps?")
            .setMessage("Are you sure? $count log${if(count==1) "" else "s"} will be deleted.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Skip and delete") { _,_ -> db.addSkippedApp(pattern); refreshLiveLogTable() }
            .show()
    }

    private fun timeline(calendar:Boolean): View {
        val root=base()
        if (calendar) {
            val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
            header.addView(title("Calendar"),LinearLayout.LayoutParams(0,-2,1f))
            header.addView(button("☷",true,18).apply { setOnClickListener { render(Page.TIMELINE) } },LinearLayout.LayoutParams(dp(52),dp(44)))
            root.addView(header); gap(root); root.addView(calendarGrid(db.getAllLogs().take(45))); gap(root); root.addView(dateRail()); return root
        }
        val controls=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(31,41,55)); setPadding(dp(8),dp(8),dp(8),dp(8)) }
        controls.addView(button("Skipped").apply { setOnClickListener { startActivity(Intent(this@MainActivity,SkippedAppsActivity::class.java)) } },LinearLayout.LayoutParams(0,dp(44),1f).apply { marginStart=dp(6) })
        root.addView(controls); gap(root,8)
        root.addView(label("Live log table",14f).apply { setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD; setPadding(dp(4),dp(4),0,dp(6)) })
        val logs=db.getAllLogs()
        if(logs.isEmpty()) root.addView(label("No tracked activity yet. The live tracker will add the foreground app here."))
        logs.forEachIndexed { i,e ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(12),dp(4),dp(12)); setBackgroundColor(if(i%2==0)Color.BLACK else Color.rgb(17,24,39)) }
            row.addView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(TextView(this@MainActivity).apply { text=e.appLabel ?: e.packageName; setTextColor(Color.rgb(209,213,219)); textSize=14f; typeface=Typeface.DEFAULT_BOLD }); addView(label("${timestamp(e.startTimestamp)}\n${formatDuration(e.duration)}")) },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(button("×",false,19).apply { setTextColor(Color.rgb(245,158,11)); contentDescription="Add to skipped apps"; setOnClickListener { db.addSkippedApp(e.packageName); render(Page.TIMELINE) } },LinearLayout.LayoutParams(dp(40),dp(42)))
            row.addView(button("⧉").apply { contentDescription="Copy package"; setOnClickListener { copy(e.packageName) } },LinearLayout.LayoutParams(dp(40),dp(42)))
            row.addView(button("⌫").apply { setTextColor(Color.rgb(153,27,27)); contentDescription="Delete log"; setOnClickListener { db.deleteLog(e.id); render(Page.TIMELINE) } },LinearLayout.LayoutParams(dp(40),dp(42)))
            root.addView(row)
        }
        return root
    }
    private fun calendarGrid(logs:List<LogEntry>)=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(navy); setPadding(dp(8),dp(8),dp(8),dp(8)); if(logs.isEmpty()) addView(label("Your tracked time blocks will appear here.")); logs.take(14).reversed().forEachIndexed { i,e -> addView(TextView(this@MainActivity).apply { text="${timestamp(e.startTimestamp)}  ${e.appLabel ?: e.packageName}\n${formatDuration(e.duration)}"; setTextColor(Color.WHITE); textSize=13f; setPadding(dp(12),dp(12),dp(12),dp(12)); setBackgroundColor(listOf(Color.rgb(92,94,44),Color.rgb(47,82,66),Color.rgb(49,77,119),Color.rgb(143,83,45))[i%4]) },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(3) }) } }
    private fun dateRail()=LinearLayout(this).apply { gravity=Gravity.CENTER; listOf("‹","Thu 6","Fri 7","Sat Aug 8","Sun 9","+","…").forEach { t -> addView(button(t,t=="Sat Aug 8"),LinearLayout.LayoutParams(0,dp(44),if(t.length>2)1.4f else .7f).apply { marginEnd=dp(3) }) } }

    private fun usageChart(): View {
        val root=base(); root.addView(title("Usage")); gap(root,10); root.addView(usagePeriodSelector { usagePeriod=it; showAllHomeApps=false; render(Page.USAGE_CHART) }); gap(root)
        val start=usageRangeStartMs(usagePeriod); val logs=usageLogs()
        val daysInMonth=Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
        val customDays=customRangeEndMs?.let { ((it-start)/86_400_000L).toInt()+1 } ?: 1
        val daily=usagePeriod!="Today"; val values=MutableList(if(!daily)24 else if(usagePeriod=="Week")7 else if(usagePeriod=="Custom") customDays else daysInMonth) { 0L }
        logs.forEach { log -> val index=if(daily) ((log.startTimestamp-start)/86_400_000L).toInt() else ((log.startTimestamp-start)/3_600_000L).toInt(); if(index in values.indices) values[index]+=log.duration }
        val labels=when(usagePeriod) { "Today" -> hourlyAxisLabels(); "Week" -> listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun"); "Custom" -> listOf("Start","End"); else -> listOf("1","8","15","22",daysInMonth.toString()) }
        root.addView(card().apply {
            val cardHeader=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL }
            cardHeader.addView(label(if(usagePeriod=="Today")"Usage by time" else "Usage by day",17f).apply { setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD },LinearLayout.LayoutParams(0,-2,1f))
            val modeSwitch=LinearLayout(this@MainActivity).apply { setBackgroundColor(Color.rgb(42,47,60)); setPadding(dp(2),dp(2),dp(2),dp(2)) }
            modeSwitch.addView(button("◔",false,20).apply { setOnClickListener { render(Page.OVERVIEW) } },LinearLayout.LayoutParams(dp(42),dp(38)))
            modeSwitch.addView(button("⌁",true,20),LinearLayout.LayoutParams(dp(42),dp(38)))
            cardHeader.addView(modeSwitch); addView(cardHeader)
            addView(label(if(usagePeriod=="Today")"Today" else if(usagePeriod=="Week")"Monday to Sunday" else "This month",13f).apply { setPadding(0,dp(5),0,0) })
            addView(UsageTrendChartView(this@MainActivity,values,labels,useBarUsageChart()),LinearLayout.LayoutParams(-1,dp(270))); addView(label("${formatDuration(values.sum())} total",14f).apply { gravity=Gravity.END })
        })
        gap(root)
        val appSlices=usageAppSlices(logs)
        root.addView(card().apply {
            val listHeader=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL }
            listHeader.addView(title("Apps").apply { textSize=17f },LinearLayout.LayoutParams(0,-2,1f))
            listHeader.addView(button("Browser",usageBrowserOnly,13).apply { setOnClickListener { usageBrowserOnly=!usageBrowserOnly; render(Page.USAGE_CHART) } },LinearLayout.LayoutParams(dp(88),dp(38)))
            addView(listHeader)
            appSlices.take(if(showAllHomeApps) appSlices.size else 5).forEach { addUsageRow(this,it.label,it.seconds,values.sum(),it.color) }
            if(!showAllHomeApps && appSlices.size>5) addView(button("Show more apps (${appSlices.size-5})").apply { setOnClickListener { showAllHomeApps=true; render(Page.USAGE_CHART) } },LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin=dp(8) })
        })
        return root
    }

    private fun calendarDayScreen(): View {
        val root=base(); val start=calendarDayStart(); val end=start+86_400_000L
        val blocks=buildCalendarBlocks(db.getAllLogs().filter { it.startTimestamp in start until end })
        root.addView(title("Calendar day view")); root.addView(label("${SimpleDateFormat("EEE, MMM d",Locale.getDefault()).format(Date(start))} · starts ${dayStartHour()}:00")); gap(root,8)
        CalendarDayView.HOUR_OFFSET=dayStartHour()
        root.addView(card().apply { setPadding(dp(4),dp(4),dp(4),dp(4)); if(blocks.isEmpty()) addView(label("No timeblocks yet for this day.").apply { gravity=Gravity.CENTER; setPadding(0,dp(260),0,dp(260)) }) else addView(CalendarDayView(this@MainActivity,start,blocks,calendarDayZoom,calendarDayPanHours) { zoom,pan -> calendarDayZoom=zoom; calendarDayPanHours=pan },LinearLayout.LayoutParams(-1,dp(720))) })
        return root
    }
    private fun calendarDayPicker() = LinearLayout(this).apply {
        gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(14,16,21)); setPadding(dp(3),dp(6),dp(3),dp(6))
        val selected=calendarDayStart(); val first=firstLiveLogDay(); val today=trackingDayStart(System.currentTimeMillis())
        (-2..2).forEach { offset ->
            val day=selected+offset*86_400_000L; val enabled=day in first..today
            addView(Button(this@MainActivity).apply {
                text=SimpleDateFormat("EEE\nd",Locale.getDefault()).format(Date(day)); textSize=11f; transformationMethod=null
                setTextColor(if(enabled) Color.rgb(210,213,220) else Color.rgb(78,82,91)); setBackgroundColor(if(day==selected) Color.rgb(42,47,60) else Color.TRANSPARENT); isEnabled=enabled
                if(enabled) setOnClickListener { calendarDayStartMs=day; render(Page.BROWSER) }
            },LinearLayout.LayoutParams(0,dp(52),1f))
        }
        addView(button("↺",false,22).apply { contentDescription="Return to today"; isEnabled=selected!=today; setOnClickListener { calendarDayStartMs=today; render(Page.BROWSER) } },LinearLayout.LayoutParams(dp(48),dp(52)).apply { marginStart=dp(5) })
        addView(button("▣",false,20).apply { contentDescription="Choose calendar date"; setOnClickListener { showCalendarDayDatePicker() } },LinearLayout.LayoutParams(dp(48),dp(52)).apply { marginStart=dp(5) })
    }
    private fun calendarDayStart(): Long = calendarDayStartMs ?: trackingDayStart(System.currentTimeMillis()).also { calendarDayStartMs=it }
    private fun showCalendarDayDatePicker() {
        val first=firstLiveLogDay(); val today=trackingDayStart(System.currentTimeMillis()); val initial=Calendar.getInstance().apply { timeInMillis=calendarDayStart() }
        DatePickerDialog(this,{ _,year,month,day ->
            calendarDayStartMs=Calendar.getInstance().apply { set(year,month,day,dayStartHour(),0,0); set(Calendar.MILLISECOND,0) }.timeInMillis
            render(Page.BROWSER)
        },initial.get(Calendar.YEAR),initial.get(Calendar.MONTH),initial.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate=first; datePicker.maxDate=today }.show()
    }
    private fun buildCalendarBlocks(logs:List<LogEntry>):List<CalendarDayBlock> {
        val prefs=getSharedPreferences("TimeTrackerPrefs",MODE_PRIVATE); val minLog=prefs.getInt("min_log_duration",1).coerceAtLeast(1); val maxAttach=prefs.getInt("max_attach_distance",400).coerceAtLeast(0); val lookahead=prefs.getInt("lookahead_window",500).coerceAtLeast(0); val minBlock=prefs.getInt("min_timeblock_duration",300).coerceAtLeast(1)
        val result=mutableListOf<CalendarDayBlock>()
        val ordered=logs.sortedBy { it.startTimestamp }
        ordered.filter { it.duration>=minLog }.forEach { log ->
            val end=log.endTimestamp ?: log.startTimestamp+log.duration*1000L; val previous=result.lastOrNull()
            // Same first-pass rule as the week view: only overlapping logs form a block.
            if(previous!=null && previous.packageName==log.packageName && log.startTimestamp<=previous.endMs) result[result.lastIndex]=previous.copy(endMs=maxOf(previous.endMs,end),activeSeconds=previous.activeSeconds+log.duration)
            else result.add(calendarBlockFor(log,end))
        }
        ordered.filter { it.duration<minLog }.forEach { log ->
            val logEnd=log.endTimestamp ?: log.startTimestamp+log.duration*1000L
            val index=result.indices.filter { result[it].packageName==log.packageName }.minByOrNull { i ->
                val block=result[i]; if(logEnd<block.startMs) block.startMs-logEnd else if(log.startTimestamp>block.endMs) log.startTimestamp-block.endMs else 0L
            } ?: return@forEach
            val block=result[index]; val distance=if(logEnd<block.startMs) block.startMs-logEnd else if(log.startTimestamp>block.endMs) log.startTimestamp-block.endMs else 0L
            if(distance<=maxAttach*1000L) result[index]=block.copy(activeSeconds=block.activeSeconds+log.duration)
        }
        // Same second pass as the week view, but app package replaces desktop category.
        val merged=mutableListOf<CalendarDayBlock>()
        result.sortedBy { it.startMs }.forEach { candidate ->
            val existingIndex=merged.indexOfLast { it.packageName==candidate.packageName && candidate.startMs-it.endMs<=lookahead*1000L }
            if(existingIndex>=0) {
                val existing=merged[existingIndex]
                merged[existingIndex]=existing.copy(endMs=maxOf(existing.endMs,candidate.endMs),activeSeconds=existing.activeSeconds+candidate.activeSeconds)
            } else merged.add(candidate)
        }
        return merged.filter { it.activeSeconds>=minBlock }.sortedBy { it.startMs }
    }
    private fun calendarBlockFor(log:LogEntry,end:Long):CalendarDayBlock {
        val icon=runCatching { packageManager.getApplicationIcon(log.packageName) }.getOrNull()
        return CalendarDayBlock(log.packageName,genericAppLabel(log.packageName),log.startTimestamp,end,log.duration,AppUsageDonutView.colorFromIcon(icon),icon)
    }
    private fun statistics(totalMode:Boolean): View {
        val root=base(); val (start,end)=statisticsRange(); val logs=db.getAllLogs().filter { it.startTimestamp in start until end }; val seconds=logs.sumOf { it.duration }; val days=max(1,((end-start)/86_400_000L).toInt())
        root.addView(title(if(totalMode)"Total Statistics" else "Daily Average")); gap(root)
        root.addView(LinearLayout(this).apply {
            addView(button("Reset").apply { setOnClickListener { statisticsRangeStartMs=null; statisticsRangeEndMs=null; showAllStatisticsApps=false; render(if(totalMode)Page.TOTAL else Page.DAILY) } },LinearLayout.LayoutParams(0,dp(44),.7f))
            addView(button(statisticsRangeLabel(start,end)).apply { setOnClickListener { showStatisticsRangePicker(totalMode) } },LinearLayout.LayoutParams(0,dp(44),2f).apply { marginStart=dp(8) })
        }); gap(root)
        root.addView(LinearLayout(this).apply { addView(button("Daily Avg",!totalMode).apply { setOnClickListener { render(Page.DAILY) } },LinearLayout.LayoutParams(0,dp(46),1f)); addView(button("Total",totalMode).apply { setOnClickListener { render(Page.TOTAL) } },LinearLayout.LayoutParams(0,dp(46),1f)) }); gap(root)
        root.addView(card().apply { addView(label(if(totalMode)"Total Time" else "Average per day")); addView(title(formatDuration(if(totalMode)seconds else seconds/days)).apply { textSize=31f }) }); gap(root)
        if(totalMode) db.getAllLogs().minOfOrNull { it.startTimestamp }?.let { first ->
            root.addView(card().apply { addView(label("First Active Day")); addView(TextView(this@MainActivity).apply { text=SimpleDateFormat("MMM d, yyyy",Locale.getDefault()).format(Date(first)); setTextColor(Color.WHITE); textSize=18f; typeface=Typeface.DEFAULT_BOLD }); addView(label(timeSince(first)).apply { setPadding(0,dp(3),0,0) }) })
            gap(root)
        }
        val appSlices=usageAppSlices(logs); val visible=if(showAllStatisticsApps) appSlices else appSlices.take(5)
        root.addView(card().apply { addView(title(if(totalMode)"Top Apps" else "Daily app averages").apply { textSize=17f }); visible.forEach { slice -> addUsageRow(this,slice.label,if(totalMode)slice.seconds else slice.seconds/days,if(totalMode)seconds else max(1,seconds/days),slice.color) }; if(!showAllStatisticsApps && appSlices.size>5) addView(button("Show more apps (${appSlices.size-5})").apply { setOnClickListener { showAllStatisticsApps=true; render(if(totalMode)Page.TOTAL else Page.DAILY) } },LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin=dp(8) }) }); gap(root)
        if(!totalMode) {
            val hourly=MutableList(24) { 0L }; logs.forEach { log -> val hour=((log.startTimestamp-start)%86_400_000L/3_600_000L).toInt(); if(hour in hourly.indices) hourly[hour]+=log.duration }
            val labels=hourlyAxisLabels()
            root.addView(card().apply { addView(title("Daily Usage Time").apply { textSize=17f }); addView(label("Average by hour across the selected range").apply { setPadding(0,dp(4),0,0) }); addView(UsageTrendChartView(this@MainActivity,hourly.map { it/days },labels,useBarUsageChart()),LinearLayout.LayoutParams(-1,dp(270))) })
        }
        return root
    }
    private fun settings(): View { val root=base(); root.addView(title("Settings")); gap(root)
        root.addView(card().apply {
            addView(CheckBox(this@MainActivity).apply {
                text="Use bar graph"; setTextColor(Color.WHITE); textSize=16f; isChecked=useBarUsageChart()
                setOnCheckedChangeListener { _,checked -> getSharedPreferences("TimeTrackerPrefs",MODE_PRIVATE).edit().putBoolean("use_bar_usage_chart",checked).apply() }
            })
        }); gap(root)
        root.addView(card().apply {
            addView(CheckBox(this@MainActivity).apply {
                text="Enable Notes"; setTextColor(Color.WHITE); textSize=16f; isChecked=notesEnabled()
                setOnCheckedChangeListener { _,checked -> getSharedPreferences("TimeTrackerPrefs",MODE_PRIVATE).edit().putBoolean("notes_enabled",checked).apply(); render(Page.SETTINGS) }
            })
            addView(label("Turning this off only hides Notes; your saved notes remain on this device."))
        }); gap(root)
        root.addView(card().apply {
            addView(TextView(this@MainActivity).apply { text="Start hour"; setTextColor(Color.WHITE); textSize=16f })
            addView(label("Daily usage begins at this hour. Week starts Monday and Month starts on the first at the same hour.").apply { setPadding(0,dp(4),0,dp(8)) })
            addView(EditText(this@MainActivity).apply {
                inputType=InputType.TYPE_CLASS_NUMBER; setText(dayStartHour().toString()); setSelectAllOnFocus(false); setTextColor(Color.WHITE); setHintTextColor(muted); hint="6"; backgroundTintList=android.content.res.ColorStateList.valueOf(purple)
                setOnFocusChangeListener { _, focused -> if (!focused) saveDayStartHour(text.toString()) }
                setOnEditorActionListener { _, _, _ -> saveDayStartHour(text.toString()); clearFocus(); true }
            },LinearLayout.LayoutParams(-1,dp(52)))
        }); gap(root)
        root.addView(card().apply { addView(title("App display").apply { textSize=16f }); addTimeblockField("Min app duration (sec)","min_app_duration",0) }); gap(root)
        root.addView(timeBlockSettingsCard()); return root }
    private fun syncView(): View {
        val root=base(); root.addView(title("Sync")); gap(root,8)
        if(!syncManager.hasServerIp() || isChangingSyncServer) {
            root.addView(card().apply {
                addView(title("Connect to server").apply { textSize=17f })
                addView(label("Enter your sync server IP to upload logs and pull from other devices.").apply { setPadding(0,dp(4),0,dp(10)) })
                val row=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL }
                val input=EditText(this@MainActivity).apply { setSingleLine(true); inputType=InputType.TYPE_CLASS_TEXT; setText(syncManager.getServerIp() ?: SyncManager.DEFAULT_SERVER_IP); setTextColor(Color.WHITE); backgroundTintList=android.content.res.ColorStateList.valueOf(purple) }
                val connect=button("Connect",true).apply { setOnClickListener { isEnabled=false; text="Checking…"; syncManager.checkServer(input.text.toString()) { result -> runOnUiThread { result.fold(onSuccess={ isChangingSyncServer=false; render(Page.SYNC) },onFailure={ isEnabled=true; text="Connect"; Toast.makeText(this@MainActivity,it.message ?: "Connection failed",Toast.LENGTH_LONG).show() }) } } } }
                input.setOnEditorActionListener { _,_,_ -> connect.performClick(); true }; row.addView(input,LinearLayout.LayoutParams(0,dp(52),1f)); row.addView(connect,LinearLayout.LayoutParams(dp(98),dp(52)).apply { marginStart=dp(8) }); addView(row)
            })
            return root
        }
        val registered=syncManager.isRegistered(); val active=syncManager.isActive()
        root.addView(card().apply {
            val head=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL }; head.addView(LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL; addView(label("SERVER").apply { textSize=11f }); addView(TextView(this@MainActivity).apply { text=syncManager.getServerIp(); setTextColor(Color.WHITE); textSize=16f; typeface=Typeface.MONOSPACE }) },LinearLayout.LayoutParams(0,-2,1f)); head.addView(button("Change server").apply { setOnClickListener { isChangingSyncServer=true; render(Page.SYNC) } },LinearLayout.LayoutParams(dp(120),dp(42))); addView(head)
            if(registered && active) { val syncRow=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(14),0,0); addView(LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL; addView(label("NEXT SYNC").apply { textSize=11f }); val countdown=title(formatSyncCountdown(syncManager.secondsUntilNextAutoPush())).apply { textSize=22f }; syncCountdownText=countdown; addView(countdown) },LinearLayout.LayoutParams(0,-2,1f)); addView(button("Sync now",true).apply { setOnClickListener { syncManager.sync { ok,message -> runOnUiThread { Toast.makeText(this@MainActivity,message,Toast.LENGTH_LONG).show(); if(ok) render(Page.SYNC) } } } },LinearLayout.LayoutParams(dp(98),dp(46))) }; addView(syncRow) }
        }); gap(root)
        root.addView(card().apply {
            val head=LinearLayout(this@MainActivity).apply {
                gravity=Gravity.CENTER_VERTICAL
                addView(title("Local device").apply { textSize=17f },LinearLayout.LayoutParams(0,-2,1f))
                addView(label(if(active)"Active" else if(registered)"Waiting for approval" else "Unregistered").apply { setTextColor(if(active)Color.rgb(134,239,172) else if(registered)Color.rgb(253,230,138) else Color.rgb(248,113,113)) })
            }
            addView(head)
            if(!registered) {
                addView(label("DEVICE NAME").apply { setPadding(0,dp(12),0,dp(4)) })
                val deviceName=EditText(this@MainActivity).apply { setSingleLine(true); inputType=InputType.TYPE_CLASS_TEXT; setText(syncManager.defaultDeviceName()); setTextColor(Color.WHITE); backgroundTintList=android.content.res.ColorStateList.valueOf(purple) }
                addView(deviceName,LinearLayout.LayoutParams(-1,dp(52)))
                addView(button("Register this device",true).apply { setOnClickListener { val name=deviceName.text.toString(); AlertDialog.Builder(this@MainActivity).setTitle("Register this device?").setMessage("Registering $name means uploading your logs to the server.").setNegativeButton("Cancel",null).setPositiveButton("Register") { _,_-> syncManager.register(name) { ok,message -> runOnUiThread { Toast.makeText(this@MainActivity,message,Toast.LENGTH_LONG).show(); if(ok) render(Page.SYNC) } } }.show() } },LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(12) })
            }
            else { addView(label("Local UUID\n${syncManager.getDeviceUuid() ?: "—"}").apply { setPadding(0,dp(10),0,0) }); if(!active) addView(button("Check approval").apply { setOnClickListener { syncManager.checkActivation { ok,message -> runOnUiThread { Toast.makeText(this@MainActivity,message,Toast.LENGTH_LONG).show(); if(ok) render(Page.SYNC) } } } },LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(12) }) else addView(button("Reupload all logs").apply { setOnClickListener { syncManager.reuploadAllLogs { _,message -> runOnUiThread { Toast.makeText(this@MainActivity,message,Toast.LENGTH_LONG).show() } } } },LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(12) }) }
        }); gap(root)
        if(active) root.addView(card().apply {
            val heading=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL; addView(title("Other devices").apply { textSize=17f },LinearLayout.LayoutParams(0,-2,1f)); addView(button("Refresh").apply { setOnClickListener { refreshSyncOtherDevices() } },LinearLayout.LayoutParams(dp(82),dp(40))) }; addView(heading)
            addView(label("Device names refresh from the server. Subscriptions are unavailable on mobile.").apply { setPadding(0,dp(4),0,dp(10)) })
            val other=syncOtherDevices?.filter { it.uuid!=syncManager.getDeviceUuid() }
            when { other==null -> { refreshSyncOtherDevices(); addView(label("Loading devices…")) }; other.isEmpty() -> addView(label("No other active devices on the server yet.")); else -> other.forEach { device -> val row=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(6),0,dp(6)); addView(LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL; addView(TextView(this@MainActivity).apply { text=device.name; setTextColor(Color.WHITE); textSize=15f }); addView(label(device.uuid)) },LinearLayout.LayoutParams(0,-2,1f)); addView(button("Subscribe").apply { isEnabled=false; alpha=.5f; contentDescription="Subscriptions unavailable on mobile" },LinearLayout.LayoutParams(dp(98),dp(42))) }; addView(row) } }
        })
        return root
    }
    private fun refreshSyncOtherDevices() { syncOtherDevices=null; syncManager.fetchActiveDevices { result -> runOnUiThread { syncOtherDevices=result.getOrElse { emptyList() }; if(current==Page.SYNC) render(Page.SYNC) } } }
    private fun formatSyncCountdown(seconds:Long?):String { if(seconds==null) return "—"; return "%d:%02d".format(seconds/60,seconds%60) }
    private fun timeBlockSettingsCard() = card().apply {
        addView(title("Timeblock detection (advanced)").apply { textSize=16f })
        addTimeblockField("Min log duration (sec)","min_log_duration",1)
        addTimeblockField("Max attach distance (sec)","max_attach_distance",400)
        addTimeblockField("Lookahead window (sec)","lookahead_window",500)
        addTimeblockField("Min timeblock duration (sec)","min_timeblock_duration",300)
    }
    private fun LinearLayout.addTimeblockField(labelText:String,key:String,default:Int) {
        val row=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(7),0,0) }
        row.addView(label(labelText,14f),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(EditText(this@MainActivity).apply { inputType=InputType.TYPE_CLASS_NUMBER; setText(getSharedPreferences("TimeTrackerPrefs",MODE_PRIVATE).getInt(key,default).toString()); setTextColor(Color.WHITE); textSize=14f; setSelectAllOnFocus(false); setOnFocusChangeListener { _,focused -> if(!focused) text.toString().toIntOrNull()?.takeIf { it>=0 }?.let { getSharedPreferences("TimeTrackerPrefs",MODE_PRIVATE).edit().putInt(key,it).apply() } } },LinearLayout.LayoutParams(dp(76),dp(44)))
        addView(row)
    }
    private fun action(text:String,onClick:()->Unit)=button(text).apply { gravity=Gravity.START or Gravity.CENTER_VERTICAL; setOnClickListener { onClick() } }
    private fun permissionCard() = card().apply {
        setBackgroundColor(Color.rgb(31, 43, 69))
        addView(title("Optional tracking").apply { textSize = 17f })
        addView(label("Usage Access is enabled. Add these sources only if you want browser and media activity in your logs.").apply { setPadding(0, dp(4), 0, dp(10)) })
        if (!isBrowserTrackingEnabled()) addView(permissionAction("Enable browser activity") { openAccessibilitySettings() })
        if (!isMediaTrackingEnabled()) addView(permissionAction("Enable media playback") { openNotificationAccessSettings() })
    }
    private fun permissionAction(name: String, onClick: () -> Unit) = button(name).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }
    private fun hasAllTrackingPermissions() = UsageLogImporter.hasUsageAccess(this) && isBrowserTrackingEnabled() && isMediaTrackingEnabled()
    private fun isBrowserTrackingEnabled(): Boolean {
        val enabled = (getSystemService(ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager)
            ?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo.serviceInfo.packageName == packageName } == true
        return enabled || (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(packageName)
    }
    private fun isMediaTrackingEnabled() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName) ||
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
    private fun openUsageAccess() = startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    private fun openAccessibilitySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    private fun openNotificationAccessSettings() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    private fun requireUsageAccess() {
        if (UsageLogImporter.hasUsageAccess(this)) {
            startLiveTracking()
            return
        }
        if (isFinishing || usageAccessDialog?.isShowing == true) return
        usageAccessDialog = AlertDialog.Builder(this)
            .setTitle("Usage Access required")
            .setMessage("Usage Access is required to use Time Tracker. Browser activity and media playback are optional and can be enabled later.")
            .setPositiveButton("Open Usage Access") { _, _ -> openUsageAccess() }
            .create()
            .apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                show()
            }
    }
    private fun startLiveTracking() {
        val intent = Intent(this, UsageTrackerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }
    private fun timestamp(ms:Long)=SimpleDateFormat("hh:mm:ss a MMM d, yyyy",Locale.getDefault()).format(Date(ms))
    private fun minAppDuration()=getSharedPreferences("TimeTrackerPrefs",MODE_PRIVATE).getInt("min_app_duration",0).coerceAtLeast(0).toLong()
    private fun timeSince(start:Long):String {
        val from=Calendar.getInstance().apply { timeInMillis=start }; val to=Calendar.getInstance()
        var months=(to.get(Calendar.YEAR)-from.get(Calendar.YEAR))*12+to.get(Calendar.MONTH)-from.get(Calendar.MONTH)
        if(to.get(Calendar.DAY_OF_MONTH)<from.get(Calendar.DAY_OF_MONTH)) months--
        val anchor=(from.clone() as Calendar).apply { add(Calendar.MONTH,months.coerceAtLeast(0)) }
        val days=((to.timeInMillis-anchor.timeInMillis)/86_400_000L).coerceAtLeast(0)
        return listOfNotNull(months.takeIf { it>0 }?.let { "$it month${if(it==1)"" else "s"}" },days.takeIf { it>0 }?.let { "$it day${if(it==1L)"" else "s"}" }).ifEmpty { listOf("Today") }.joinToString(" ")
    }
    private fun notesEnabled()=getSharedPreferences("TimeTrackerPrefs",MODE_PRIVATE).getBoolean("notes_enabled",true)
    private fun hourlyAxisLabels()=listOf(0,6,12,18,24).map { chartHourLabel(dayStartHour()+it) }
    private fun chartHourLabel(hour:Int):String { val h=(hour%24+24)%24; return when(h) { 0 -> "Midnight"; 12 -> "Noon"; in 1..11 -> "${h}am"; else -> "${h-12}pm" } }
    private fun statisticsRange():Pair<Long,Long> {
        val first=db.getAllLogs().minOfOrNull { trackingDayStart(it.startTimestamp) } ?: trackingDayStart(System.currentTimeMillis())
        val today=trackingDayStart(System.currentTimeMillis())
        val start=(statisticsRangeStartMs ?: first).coerceIn(first,today)
        val end=(statisticsRangeEndMs ?: today+86_400_000L).coerceIn(start+1,today+86_400_000L)
        return start to end
    }
    private fun statisticsRangeLabel(start:Long,end:Long):String {
        val fmt=SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()); val today=trackingDayStart(System.currentTimeMillis())
        return "${fmt.format(Date(start))}  →  ${if(end>=today+86_400_000L) "Today" else fmt.format(Date(end-1))}"
    }
    private fun showStatisticsRangePicker(totalMode:Boolean) {
        val first=db.getAllLogs().minOfOrNull { trackingDayStart(it.startTimestamp) } ?: run { Toast.makeText(this,"No logs available yet",Toast.LENGTH_SHORT).show(); return }
        val today=trackingDayStart(System.currentTimeMillis()); val initial=Calendar.getInstance().apply { timeInMillis=statisticsRange().first }
        DatePickerDialog(this,{ _,year,month,day ->
            val selected=Calendar.getInstance().apply { set(year,month,day,dayStartHour(),0,0); set(Calendar.MILLISECOND,0) }.timeInMillis
            showStatisticsRangeEndPicker(first,today,selected,totalMode)
        },initial.get(Calendar.YEAR),initial.get(Calendar.MONTH),initial.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate=first; datePicker.maxDate=today }.show()
    }
    private fun showStatisticsRangeEndPicker(first:Long,today:Long,start:Long,totalMode:Boolean) {
        val initial=Calendar.getInstance().apply { timeInMillis=(statisticsRangeEndMs?.minus(1) ?: today).coerceIn(start,today) }
        DatePickerDialog(this,{ _,year,month,day ->
            statisticsRangeStartMs=start
            statisticsRangeEndMs=Calendar.getInstance().apply { set(year,month,day,dayStartHour(),0,0); set(Calendar.MILLISECOND,0); add(Calendar.DAY_OF_YEAR,1) }.timeInMillis
            showAllStatisticsApps=false; render(if(totalMode)Page.TOTAL else Page.DAILY)
        },initial.get(Calendar.YEAR),initial.get(Calendar.MONTH),initial.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate=maxOf(first,start); datePicker.maxDate=today }.show()
    }
    private fun showCustomRangePicker() {
        val first=db.getAllLogs().minOfOrNull { it.startTimestamp } ?: run { Toast.makeText(this,"No logs available yet",Toast.LENGTH_SHORT).show(); return }
        val latest=System.currentTimeMillis()
        val initial=Calendar.getInstance().apply { timeInMillis=customRangeStartMs ?: first }
        DatePickerDialog(this,{ _,year,month,day ->
            val selected=Calendar.getInstance().apply { set(year,month,day,dayStartHour(),0,0); set(Calendar.MILLISECOND,0) }.timeInMillis
            showCustomRangeEndPicker(first,latest,selected)
        },initial.get(Calendar.YEAR),initial.get(Calendar.MONTH),initial.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate=first; datePicker.maxDate=latest
        }.show()
    }
    private fun showCustomRangeEndPicker(first:Long, latest:Long, start:Long) {
        val initial=Calendar.getInstance().apply { timeInMillis=(customRangeEndMs ?: latest).coerceAtLeast(start).coerceAtMost(latest) }
        DatePickerDialog(this,{ _,year,month,day ->
            val end=Calendar.getInstance().apply { set(year,month,day,dayStartHour(),0,0); set(Calendar.MILLISECOND,0); add(Calendar.DAY_OF_YEAR,1); add(Calendar.MILLISECOND,-1) }.timeInMillis
            customRangeStartMs=start; customRangeEndMs=end; usagePeriod="Custom"; showAllHomeApps=false; render(if(current==Page.USAGE_CHART) Page.USAGE_CHART else Page.OVERVIEW)
        },initial.get(Calendar.YEAR),initial.get(Calendar.MONTH),initial.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate=maxOf(first,start); datePicker.maxDate=latest
        }.show()
    }
    private fun usageLogs(): List<LogEntry> = db.getAllLogs().filter { entry ->
        entry.startTimestamp >= usageRangeStartMs(usagePeriod) && entry.startTimestamp <= usageRangeEndMs() && (!usageBrowserOnly || BrowserTitleCache.isBrowserPackage(entry.packageName))
    }
    private fun useBarUsageChart() = getSharedPreferences("TimeTrackerPrefs",MODE_PRIVATE).getBoolean("use_bar_usage_chart",false)
    private fun dayStartHour() = getSharedPreferences("TimeTrackerPrefs", MODE_PRIVATE).getInt("day_start_hour", 6).coerceIn(0,23)
    private fun saveDayStartHour(value: String) { value.toIntOrNull()?.takeIf { it in 0..23 }?.let { getSharedPreferences("TimeTrackerPrefs", MODE_PRIVATE).edit().putInt("day_start_hour",it).apply() } ?: Toast.makeText(this,"Start hour must be from 0 to 23",Toast.LENGTH_SHORT).show() }
    private fun usageRangeStartMs(period: String): Long {
        if (period == "Custom") return customRangeStartMs ?: db.getAllLogs().minOfOrNull { it.startTimestamp } ?: System.currentTimeMillis()
        val now=Calendar.getInstance(); val start=Calendar.getInstance().apply { timeInMillis=now.timeInMillis; set(Calendar.HOUR_OF_DAY,dayStartHour()); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
        when(period) {
            "Today" -> if(now.timeInMillis < start.timeInMillis) start.add(Calendar.DAY_OF_YEAR,-1)
            "Week" -> { start.set(Calendar.DAY_OF_WEEK,Calendar.MONDAY); if(now.timeInMillis < start.timeInMillis) start.add(Calendar.WEEK_OF_YEAR,-1) }
            "Month" -> { start.set(Calendar.DAY_OF_MONTH,1); if(now.timeInMillis < start.timeInMillis) start.add(Calendar.MONTH,-1) }
        }
        return start.timeInMillis
    }
    private fun usageRangeEndMs() = if(usagePeriod=="Custom") customRangeEndMs ?: System.currentTimeMillis() else Long.MAX_VALUE
    private fun formatDuration(seconds:Long):String { val h=seconds/3600; val m=(seconds%3600)/60; val s=seconds%60; return if(h>0)"${h}h ${m}m ${s}s" else if(m>0)"${m}m ${s}s" else "${s}s" }
    private fun copy(text:String) { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Time Tracker",text)); Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show() }
    private fun importUsageSilently() {
        if (isImportingUsage) return
        isImportingUsage = true
        Thread {
            UsageLogImporter(applicationContext).importNow()
            runOnUiThread {
                isImportingUsage = false
                if (!isFinishing) render(current)
            }
        }.start()
    }
    private fun importUsageForLiveTable() {
        if (!UsageLogImporter.hasUsageAccess(this) || isImportingUsage) { refreshLiveLogTable(); return }
        isImportingUsage = true
        Thread {
            UsageLogImporter(applicationContext).importNow()
            runOnUiThread {
                isImportingUsage = false
                if (current == Page.TIMELINE && !isFinishing) refreshLiveLogTable()
            }
        }.start()
    }
    private fun importUsageForCurrentScreen() {
        if (!UsageLogImporter.hasUsageAccess(this) || isImportingUsage) return
        isImportingUsage = true
        Thread {
            UsageLogImporter(applicationContext).importNow()
            runOnUiThread {
                isImportingUsage = false
                if (current != Page.TIMELINE && !isFinishing) render(current)
            }
        }.start()
    }
    companion object { const val EXTRA_START_PAGE = "time_tracker_start_page" }
}
