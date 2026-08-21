package ca.tariq_sekhri.time_tracker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
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
    private lateinit var screen: FrameLayout
    private lateinit var nav: LinearLayout
    private var activeScroll: ScrollView? = null
    private var liveLogList: ListView? = null
    private var usageAccessDialog: AlertDialog? = null
    private var isImportingUsage = false
    private val timelineRefreshHandler = Handler(Looper.getMainLooper())
    private val backgroundImportHandler = Handler(Looper.getMainLooper())
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
    private enum class Page { OVERVIEW, USAGE_CHART, TIMELINE, CALENDAR, BROWSER, DAILY, TOTAL, SETTINGS }
    private val bg = Color.rgb(17, 19, 24); private val panel = Color.rgb(28, 32, 42)
    private val navy = Color.rgb(22, 32, 52); private val purple = Color.rgb(177, 139, 255); private val muted = Color.rgb(157, 164, 178)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        screen = FrameLayout(this); root.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(12)); setBackgroundColor(Color.rgb(14, 16, 21)) }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(68))); setContentView(root); render(Page.OVERVIEW)
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
        super.onPause()
    }
    private fun render(page: Page) {
        timelineRefreshHandler.removeCallbacks(timelineRefresh)
        current = page; screen.removeAllViews(); nav.removeAllViews()
        if (page == Page.TIMELINE) {
            activeScroll = null
            screen.addView(legacyLiveTimeline())
        } else {
            liveLogList = null
            activeScroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(bg); addView(when (page) { Page.OVERVIEW -> overview(); Page.USAGE_CHART -> usageChart(); Page.TIMELINE -> error("handled above"); Page.CALENDAR -> timeline(true); Page.BROWSER -> browserUsage(); Page.DAILY -> statistics(false); Page.TOTAL -> statistics(true); Page.SETTINGS -> settings() }) }
            screen.addView(activeScroll)
        }
        listOf("◷" to Page.OVERVIEW, "☷" to Page.TIMELINE, "▥" to Page.BROWSER, "⚙" to Page.SETTINGS).forEach { (icon, target) -> nav.addView(button(icon, target == page || (target == Page.OVERVIEW && page == Page.USAGE_CHART) || (target == Page.TIMELINE && page == Page.CALENDAR), 28).apply { layoutParams = LinearLayout.LayoutParams(0, -1, 1f); setOnClickListener { render(target) } }) }
        if (page == Page.TIMELINE) timelineRefreshHandler.postDelayed(timelineRefresh, 1_000L)
    }
    private fun base() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
    private fun title(text: String) = TextView(this).apply { this.text = text; setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
    private fun label(text: String, size: Float = 13f) = TextView(this).apply { this.text = text; setTextColor(muted); textSize = size }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(panel); setPadding(dp(14), dp(14), dp(14), dp(14)) }
    private fun gap(parent: LinearLayout, h: Int = 12) = parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(h)))
    private fun button(text: String, selected: Boolean = false, size: Int = 14) = Button(this).apply { this.text = text; textSize = size.toFloat(); transformationMethod = null; setTextColor(Color.WHITE); setBackgroundColor(if (selected) Color.rgb(101, 70, 164) else Color.rgb(42, 47, 60)); minHeight = 0; minimumHeight = 0; setPadding(dp(6), 0, dp(6), 0) }
    private fun periodTabs(selected: String, onSelect: (String) -> Unit = {}) = LinearLayout(this).apply { setBackgroundColor(Color.rgb(232,232,237)); setPadding(dp(3),dp(3),dp(3),dp(3)); listOf("Today","Week","Month").forEach { p -> addView(Button(this@MainActivity).apply { text=p; textSize=14f; transformationMethod=null; setTextColor(Color.rgb(27,29,35)); setBackgroundColor(if(p==selected) Color.rgb(216,198,255) else Color.TRANSPARENT); setOnClickListener { onSelect(p) } }, LinearLayout.LayoutParams(0,dp(48),1f)) } }

    private fun overview(): View {
        val root=base(); root.addView(title("Usage")); gap(root,10); root.addView(periodTabs(usagePeriod) { usagePeriod=it; showAllHomeApps=false; render(Page.OVERVIEW) }); gap(root)
        if (!hasAllTrackingPermissions()) {
            root.addView(permissionCard()); gap(root)
        }
        val logs=db.getAllLogs().filter { it.startTimestamp >= usageRangeStartMs(usagePeriod) && (!usageBrowserOnly || BrowserTitleCache.isBrowserPackage(it.packageName)) }; val total=logs.sumOf { it.duration }
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
        root.addView(action("Sync devices") { startActivity(Intent(this,SyncActivity::class.java)) }); gap(root,8); root.addView(action("Notes") { startActivity(Intent(this,NotesActivity::class.java)) }); gap(root,8); root.addView(action("Skipped apps") { startActivity(Intent(this,SkippedAppsActivity::class.java)) }); return root
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
        }.sortedByDescending { it.seconds }
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
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(dp(12),dp(12),dp(12),0) }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(31,41,55)); setPadding(dp(8),dp(8),dp(8),dp(8)) }
        header.addView(button("Skipped").apply { setOnClickListener { startActivity(Intent(this@MainActivity,SkippedAppsActivity::class.java)) } },LinearLayout.LayoutParams(0,dp(46),1f))
        header.addView(button("Sync").apply { setOnClickListener { startActivity(Intent(this@MainActivity,SyncActivity::class.java)) } },LinearLayout.LayoutParams(0,dp(46),1f).apply { marginStart=dp(6) })
        root.addView(header)
        liveLogList=ListView(this).apply { divider=null; dividerHeight=0; setBackgroundColor(Color.BLACK); clipToPadding=false; setPadding(0,dp(8),0,0) }
        root.addView(liveLogList,LinearLayout.LayoutParams(-1,0,1f))
        refreshLiveLogTable()
        return root
    }

    private fun refreshLiveLogTable() {
        val list=liveLogList ?: return
        val first=list.firstVisiblePosition
        val top=list.getChildAt(0)?.top ?: 0
        val logs=db.getAllLogs().filter { it.duration >= getSharedPreferences("TimeTrackerPrefs",MODE_PRIVATE).getInt("min_duration",0) }
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
        val root=base(); root.addView(title("Usage")); gap(root,10); root.addView(periodTabs(usagePeriod) { usagePeriod=it; showAllHomeApps=false; render(Page.USAGE_CHART) }); gap(root)
        val start=usageRangeStartMs(usagePeriod); val logs=db.getAllLogs().filter { it.startTimestamp>=start && (!usageBrowserOnly || BrowserTitleCache.isBrowserPackage(it.packageName)) }
        val daysInMonth=Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
        val daily=usagePeriod!="Today"; val values=MutableList(if(!daily)24 else if(usagePeriod=="Week")7 else daysInMonth) { 0L }
        logs.forEach { log -> val index=if(daily) ((log.startTimestamp-start)/86_400_000L).toInt() else ((log.startTimestamp-start)/3_600_000L).toInt(); if(index in values.indices) values[index]+=log.duration }
        val labels=when(usagePeriod) { "Today" -> listOf("12am","6am","Noon","6pm"); "Week" -> listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun"); else -> listOf("1","8","15","22",daysInMonth.toString()) }
        root.addView(card().apply {
            val cardHeader=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL }
            cardHeader.addView(label(if(usagePeriod=="Today")"Usage by time" else "Usage by day",17f).apply { setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD },LinearLayout.LayoutParams(0,-2,1f))
            val modeSwitch=LinearLayout(this@MainActivity).apply { setBackgroundColor(Color.rgb(42,47,60)); setPadding(dp(2),dp(2),dp(2),dp(2)) }
            modeSwitch.addView(button("◔",false,20).apply { setOnClickListener { render(Page.OVERVIEW) } },LinearLayout.LayoutParams(dp(42),dp(38)))
            modeSwitch.addView(button("⌁",true,20),LinearLayout.LayoutParams(dp(42),dp(38)))
            cardHeader.addView(modeSwitch); addView(cardHeader)
            addView(label(if(usagePeriod=="Today")"Today" else if(usagePeriod=="Week")"Monday to Sunday" else "This month",13f).apply { setPadding(0,dp(5),0,0) })
            addView(UsageTrendChartView(this@MainActivity,values,labels),LinearLayout.LayoutParams(-1,dp(270))); addView(label("${formatDuration(values.sum())} total",14f).apply { gravity=Gravity.END })
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

    private fun browserUsage(): View { val root=base(); root.addView(title("Browser Usage")); gap(root); root.addView(periodTabs("Month")); gap(root); val total=db.getAllLogs().sumOf { it.duration }; root.addView(card().apply { setBackgroundColor(Color.WHITE); addView(label("Total screen time").apply { setTextColor(Color.DKGRAY) }); addView(title(formatDuration(total)).apply { setTextColor(Color.rgb(25,25,30)) }); addView(label("${db.getAllLogs().map { it.packageName }.distinct().size} apps").apply { setTextColor(Color.rgb(26,140,88)) }) }); gap(root); root.addView(card().apply { addView(title("Usage over time").apply { textSize=17f }); addView(TextView(this@MainActivity).apply { text="╭╮    ╭╮\n╯╰╮ ╭╯╰\n12am     Noon     2pm"; textSize=18f; setTextColor(purple); gravity=Gravity.CENTER; setPadding(0,dp(35),0,dp(20)) }); addView(label("●  Mobile App     ${formatDuration(total)}")) }); gap(root); root.addView(card().apply { db.getAllLogs().groupBy { it.appLabel ?: it.packageName }.mapValues { it.value.sumOf { l -> l.duration } }.entries.sortedByDescending { it.value }.take(8).forEach { addUsageRow(this,it.key,it.value,total,Color.rgb(89,169,255)) } }); return root }
    private fun statistics(totalMode:Boolean): View { val root=base(); root.addView(title(if(totalMode)"Total Statistics" else "Daily Average")); gap(root); root.addView(LinearLayout(this).apply { addView(button("Reset"),LinearLayout.LayoutParams(0,dp(44),.7f)); addView(button("2026-01-22  →  Today"),LinearLayout.LayoutParams(0,dp(44),2f).apply { marginStart=dp(8) }) }); gap(root); root.addView(LinearLayout(this).apply { addView(button("Daily Avg",!totalMode).apply { setOnClickListener { render(Page.DAILY) } },LinearLayout.LayoutParams(0,dp(46),1f)); addView(button("Total",totalMode).apply { setOnClickListener { render(Page.TOTAL) } },LinearLayout.LayoutParams(0,dp(46),1f)) }); gap(root); val logs=db.getAllLogs(); val seconds=logs.sumOf { it.duration }; root.addView(card().apply { addView(label(if(totalMode)"Total Time" else "Avg Time (Active Days)")); addView(title(formatDuration(if(totalMode)seconds else seconds/max(1,logs.map { it.startTimestamp/86_400_000L }.distinct().size))).apply { textSize=31f }); if(totalMode) addView(label("First Active Day\n${logs.minOfOrNull { it.startTimestamp }?.let { SimpleDateFormat("MMM d, yyyy",Locale.getDefault()).format(Date(it)) } ?: "—"}")) }); gap(root); root.addView(card().apply { addView(title(if(totalMode)"● Top Apps" else "App averages").apply { textSize=17f }); logs.groupBy { it.appLabel ?: it.packageName }.mapValues { it.value.sumOf { x -> x.duration } }.entries.sortedByDescending { it.value }.take(6).forEach { addUsageRow(this,it.key,it.value,seconds,Color.rgb(61,164,255)) } }); gap(root); root.addView(card().apply { addView(title("Daily Usage Time").apply { textSize=17f }); addView(TextView(this@MainActivity).apply { text="▁▃▂▄▇▅▂\nJan       Apr       Aug"; setTextColor(Color.rgb(255,152,70)); textSize=24f; gravity=Gravity.CENTER; setPadding(0,dp(35),0,dp(15)) }) }); return root }
    private fun settings(): View { val root=base(); root.addView(title("Settings")); gap(root)
        root.addView(button("Sync",true,18).apply { gravity=Gravity.START or Gravity.CENTER_VERTICAL; setOnClickListener { startActivity(Intent(this@MainActivity,SyncActivity::class.java)) } },LinearLayout.LayoutParams(-1,dp(58))); gap(root,8)
        root.addView(action("Statistics") { render(Page.DAILY) }); gap(root)
        root.addView(card().apply {
            addView(TextView(this@MainActivity).apply { text="Start hour"; setTextColor(Color.WHITE); textSize=16f })
            addView(label("Daily usage begins at this hour. Week starts Monday and Month starts on the first at the same hour.").apply { setPadding(0,dp(4),0,dp(8)) })
            addView(EditText(this@MainActivity).apply {
                inputType=InputType.TYPE_CLASS_NUMBER; setText(dayStartHour().toString()); setSelectAllOnFocus(false); setTextColor(Color.WHITE); setHintTextColor(muted); hint="6"; backgroundTintList=android.content.res.ColorStateList.valueOf(purple)
                setOnFocusChangeListener { _, focused -> if (!focused) saveDayStartHour(text.toString()) }
                setOnEditorActionListener { _, _, _ -> saveDayStartHour(text.toString()); clearFocus(); true }
            },LinearLayout.LayoutParams(-1,dp(52)))
        }); gap(root)
        listOf("UI filters\nMin app duration (sec)","Timeblock detection (advanced)\n\nMin log duration (sec)\nMax attach distance (sec)\nLookahead window (sec)\nMin timeblock duration (sec)").forEach { s -> root.addView(card().apply { addView(TextView(this@MainActivity).apply { text=s; setTextColor(Color.WHITE); textSize=16f; setLineSpacing(dp(8).toFloat(),1f) }) }); gap(root) }; root.addView(action("Skipped apps") { startActivity(Intent(this,SkippedAppsActivity::class.java)) }); gap(root,8); root.addView(action("Notes") { startActivity(Intent(this,NotesActivity::class.java)) }); return root }
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
    private fun dayStartHour() = getSharedPreferences("TimeTrackerPrefs", MODE_PRIVATE).getInt("day_start_hour", 6).coerceIn(0,23)
    private fun saveDayStartHour(value: String) { value.toIntOrNull()?.takeIf { it in 0..23 }?.let { getSharedPreferences("TimeTrackerPrefs", MODE_PRIVATE).edit().putInt("day_start_hour",it).apply() } ?: Toast.makeText(this,"Start hour must be from 0 to 23",Toast.LENGTH_SHORT).show() }
    private fun usageRangeStartMs(period: String): Long {
        val now=Calendar.getInstance(); val start=Calendar.getInstance().apply { timeInMillis=now.timeInMillis; set(Calendar.HOUR_OF_DAY,dayStartHour()); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
        when(period) {
            "Today" -> if(now.timeInMillis < start.timeInMillis) start.add(Calendar.DAY_OF_YEAR,-1)
            "Week" -> { start.set(Calendar.DAY_OF_WEEK,Calendar.MONDAY); if(now.timeInMillis < start.timeInMillis) start.add(Calendar.WEEK_OF_YEAR,-1) }
            "Month" -> { start.set(Calendar.DAY_OF_MONTH,1); if(now.timeInMillis < start.timeInMillis) start.add(Calendar.MONTH,-1) }
        }
        return start.timeInMillis
    }
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
}
