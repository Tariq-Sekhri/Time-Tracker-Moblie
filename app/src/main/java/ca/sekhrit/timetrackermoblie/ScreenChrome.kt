package ca.sekhrit.timetrackermoblie

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

data class HeaderAction(
    val label: String,
    val onClick: () -> Unit
)

fun AppCompatActivity.setContentViewWithHeader(
    title: String,
    content: View,
    actions: List<HeaderAction> = emptyList(),
    showBack: Boolean = true
) {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(18, 18, 18))
    }

    val headerBaseTop = 0
    val headerBaseBottom = dp(8)
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.rgb(35, 35, 35))
        setPadding(dp(8), headerBaseTop, dp(8), headerBaseBottom)
        minimumHeight = dp(56)
    }

    if (showBack) {
        header.addView(Button(this).apply {
            text = "<"
            minWidth = dp(48)
            setOnClickListener { finish() }
        })
    }

    header.addView(TextView(this).apply {
        text = title
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
    }, LinearLayout.LayoutParams(0, -1, 1f))

    actions.forEach { action ->
        header.addView(Button(this).apply {
            text = action.label
            setOnClickListener { action.onClick() }
        })
    }

    root.addView(header, LinearLayout.LayoutParams(-1, -2))
    root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

    val contentBaseLeft = content.paddingLeft
    val contentBaseTop = content.paddingTop
    val contentBaseRight = content.paddingRight
    val contentBaseBottom = content.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        header.setPadding(
            dp(8) + systemBars.left,
            headerBaseTop + systemBars.top,
            dp(8) + systemBars.right,
            headerBaseBottom
        )
        content.setPadding(
            contentBaseLeft + systemBars.left,
            contentBaseTop,
            contentBaseRight + systemBars.right,
            contentBaseBottom + systemBars.bottom
        )
        insets
    }

    setContentView(root)
}

fun AppCompatActivity.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
