package ca.tariq_sekhri.time_tracker

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

object SyncUi {
    private const val CARD_BG = "#111827"
    private const val LABEL_COLOR = "#9CA3AF"
    private const val VALUE_COLOR = "#FFFFFF"

    fun cardPanel(activity: AppCompatActivity, block: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(CARD_BG))
            setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16))
            block()
        }
    }

    fun cardLabel(activity: AppCompatActivity, text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(Color.parseColor(LABEL_COLOR))
            textSize = 13f
        }
    }

    fun cardValue(activity: AppCompatActivity, text: String, mono: Boolean = false): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(Color.parseColor(VALUE_COLOR))
            textSize = if (mono) 15f else 16f
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(0, activity.dp(4), 0, 0)
        }
    }

    fun sectionTitle(activity: AppCompatActivity, text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    fun sectionSubtitle(activity: AppCompatActivity, text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(Color.parseColor(LABEL_COLOR))
            textSize = 13f
            setPadding(0, activity.dp(4), 0, 0)
        }
    }

    fun statusCardsRow(activity: AppCompatActivity): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(-1, -2)
            layoutParams = lp
        }
    }

    fun cardInRow(activity: AppCompatActivity, weight: Float = 1f, block: LinearLayout.() -> Unit): LinearLayout {
        return cardPanel(activity, block).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, weight).apply {
                marginEnd = activity.dp(8)
            }
        }
    }
}
