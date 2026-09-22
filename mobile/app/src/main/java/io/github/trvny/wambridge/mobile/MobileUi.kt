package io.github.trvny.wambridge.mobile

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal object MobileUi {
    enum class ButtonKind { PRIMARY, SECONDARY, QUIET, DANGER }
    enum class StatusKind { INFO, SUCCESS, ERROR }

    fun applyWindow(activity: Activity) {
        activity.window.statusBarColor = activity.getColor(R.color.wam_background)
        activity.window.navigationBarColor = activity.getColor(R.color.wam_background)
        val nightMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        activity.window.decorView.systemUiVisibility =
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
    }

    fun page(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 32))
        setBackgroundColor(context.getColor(R.color.wam_background))
    }

    fun header(context: Context, title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(context.getColor(R.color.wam_text))
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 14f
                setTextColor(context.getColor(R.color.wam_muted))
                setPadding(0, dp(context, 4), 0, dp(context, 18))
            })
        }

    fun sectionTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(context.getColor(R.color.wam_text))
        setPadding(dp(context, 2), dp(context, 22), 0, dp(context, 10))
    }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
        background = rounded(
            context,
            fill = context.getColor(R.color.wam_surface),
            stroke = context.getColor(R.color.wam_border),
            radiusDp = 20,
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(context, 12) }
    }

    fun status(context: Context, text: String = ""): TextView = TextView(context).apply {
        textSize = 14f
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        setStatus(this, text)
    }

    fun setStatus(
        view: TextView,
        text: String,
        kind: StatusKind = StatusKind.INFO,
    ) {
        val context = view.context
        val (fill, ink) = when (kind) {
            StatusKind.INFO -> R.color.wam_accent_soft to R.color.wam_text
            StatusKind.SUCCESS -> R.color.wam_success_soft to R.color.wam_success
            StatusKind.ERROR -> R.color.wam_danger_soft to R.color.wam_danger
        }
        view.text = text
        view.setTextColor(context.getColor(ink))
        view.background = rounded(
            context,
            fill = context.getColor(fill),
            stroke = context.getColor(fill),
            radiusDp = 14,
        )
    }

    fun body(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(context.getColor(R.color.wam_muted))
        setLineSpacing(0f, 1.08f)
    }

    fun heroBadge(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.wam_accent))
        background = rounded(
            context,
            fill = context.getColor(R.color.wam_accent_soft),
            stroke = context.getColor(R.color.wam_accent_soft),
            radiusDp = 22,
        )
    }

    fun heroTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(context.getColor(R.color.wam_text))
        maxLines = 2
    }

    fun heroMeta(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(context.getColor(R.color.wam_muted))
        setPadding(0, dp(context, 4), 0, 0)
        maxLines = 3
    }

    fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(context.getColor(R.color.wam_muted))
        setPadding(dp(context, 2), 0, 0, dp(context, 6))
    }

    fun field(context: Context, hint: String, multiline: Boolean = false): EditText =
        EditText(context).apply {
            this.hint = hint
            textSize = 15f
            setTextColor(context.getColor(R.color.wam_text))
            setHintTextColor(context.getColor(R.color.wam_muted))
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = rounded(
                context,
                fill = context.getColor(R.color.wam_background),
                stroke = context.getColor(R.color.wam_border),
                radiusDp = 14,
            )
            minHeight = dp(context, if (multiline) 104 else 52)
        }

    fun button(
        context: Context,
        text: String,
        kind: ButtonKind = ButtonKind.SECONDARY,
        click: () -> Unit,
    ): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(context, 48)
        minWidth = 0
        val (fill, ink, stroke) = when (kind) {
            ButtonKind.PRIMARY -> Triple(R.color.wam_accent, R.color.wam_on_accent, R.color.wam_accent)
            ButtonKind.SECONDARY -> Triple(R.color.wam_surface_alt, R.color.wam_text, R.color.wam_surface_alt)
            ButtonKind.QUIET -> Triple(R.color.wam_surface, R.color.wam_text, R.color.wam_border)
            ButtonKind.DANGER -> Triple(R.color.wam_danger_soft, R.color.wam_danger, R.color.wam_danger_soft)
        }
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(24, 0, 0, 0)),
            rounded(context, context.getColor(fill), context.getColor(stroke), 14),
            null,
        )
        setTextColor(context.getColor(ink))
        elevation = 0f
        stateListAnimator = null
        setPadding(dp(context, 10), 0, dp(context, 10), 0)
        setOnClickListener { click() }
    }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun bottomNavigation(context: Context): LinearLayout = row(context).apply {
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 10))
        background = rounded(
            context,
            fill = context.getColor(R.color.wam_surface),
            stroke = context.getColor(R.color.wam_border),
            radiusDp = 18,
        )
    }

    fun navigationButton(
        context: Context,
        text: String,
        click: () -> Unit,
    ): Button = button(context, text, ButtonKind.QUIET, click).apply {
        minHeight = dp(context, 44)
    }

    fun setNavigationSelected(button: Button, selected: Boolean) {
        val context = button.context
        button.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(24, 0, 0, 0)),
            rounded(
                context,
                fill = context.getColor(
                    if (selected) R.color.wam_accent_soft else R.color.wam_surface,
                ),
                stroke = context.getColor(
                    if (selected) R.color.wam_accent_soft else R.color.wam_surface,
                ),
                radiusDp = 14,
            ),
            null,
        )
        button.setTextColor(
            context.getColor(if (selected) R.color.wam_accent else R.color.wam_muted),
        )
    }

    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.45f
    }

    fun addWeighted(row: LinearLayout, view: View, weight: Float = 1f, marginDp: Int = 6) {
        row.addView(
            view,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                marginEnd = dp(row.context, marginDp)
            },
        )
    }

    fun rounded(context: Context, fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(context, 1), stroke)
        }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
