package com.kita.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Daftar langkah reusable; runner hanya perlu memanggil render(store.groups.value). */
class AgentStepList(context: Context) : LinearLayout(context) {
    var onApprove: ((AgentStep) -> Unit)? = null
    var onReject: ((AgentStep) -> Unit)? = null
    var onStepClick: ((AgentStep) -> Unit)? = null
    var onGroupToggle: ((String) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, dp(4))
    }

    fun render(groups: List<AgentStepGroup>) {
        removeAllViews()
        groups.forEach { group -> addView(groupView(group)) }
    }

    private fun groupView(group: AgentStepGroup): View = LinearLayout(context).apply {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(7), dp(4), dp(5))
            setOnClickListener { onGroupToggle?.invoke(group.label) }
            addView(TextView(context).apply {
                val detail = group.summary.removePrefix("${group.label} · ")
                text = if (detail == group.label) group.label else "${group.label} · $detail"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Palette.INK_SOFT)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, WRAP, 1f))
            addView(TextView(context).apply {
                text = if (group.collapsed) "›" else "⌄"
                textSize = 18f
                setTextColor(Palette.INK_SOFT)
                setSingleLine(true)
            }, LayoutParams(WRAP, WRAP))
        }
        addView(header)
        if (!group.collapsed) group.steps.forEach { addView(stepView(it)) }
    }

    private fun stepView(step: AgentStep): View {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            if (step.isApprovalWaiting) background = GradientDrawable().apply {
                setColor(Palette.FIELD)
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { onStepClick?.invoke(step) }
        }
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(context).apply {
            text = when (step.status) {
                AgentStepStatus.WAITING -> "○"
                AgentStepStatus.RUNNING -> "●"
                AgentStepStatus.SUCCESS -> "✓"
                AgentStepStatus.FAILED -> "×"
            }
            textSize = 13f
            setTextColor(statusColor(step.status))
        }
        val title = TextView(context).apply {
            text = step.title
            textSize = 14f
            setTextColor(if (step.status == AgentStepStatus.RUNNING) Palette.INK else Palette.INK_SOFT)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        top.addView(icon, LayoutParams(dp(22), WRAP))
        top.addView(title, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6) })
        top.addView(TextView(context).apply {
            text = durationLabel(step)
            textSize = 11.5f
            setTextColor(Palette.INK_SOFT)
            setSingleLine(true)
        }, LayoutParams(WRAP, WRAP))
        box.addView(top)

        if (step.isApprovalWaiting) {
            val commandScroll = ScrollView(context).apply {
                addView(TextView(context).apply {
                text = step.fullCommand
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Palette.TEXT_MAIN)
                setTextIsSelectable(true)
                setPadding(dp(28), dp(7), 0, dp(5))
                })
            }
            box.addView(commandScroll, LayoutParams(MATCH, dp(80)))
            fun pill(label: String, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
                text = label
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(if (filled) Color.WHITE else Palette.INK)
                background = pressable(GradientDrawable().apply {
                    setColor(if (filled) Palette.BRAND else Color.WHITE)
                    cornerRadius = dp(20).toFloat()
                    if (!filled) setStroke(dp(1), Palette.DIVIDER)
                })
                setPadding(dp(20), dp(9), dp(20), dp(9))
                setOnClickListener { onClick() }
            }
            val actions = LinearLayout(context).apply {
                gravity = Gravity.END
                setPadding(0, dp(6), 0, dp(2))
                addView(pill("Tolak", false) { onReject?.invoke(step) })
                addView(pill("Setuju", true) { onApprove?.invoke(step) }, LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            }
            box.addView(actions, LayoutParams(MATCH, WRAP))
        } else if (step.output.isNotBlank()) {
            box.addView(TextView(context).apply {
                text = step.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
                textSize = 11.5f
                setTextColor(Palette.TEXT_SOFT)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(28), dp(4), 0, 0)
            }, LayoutParams(MATCH, WRAP))
        }
        return box
    }

    private fun durationLabel(step: AgentStep): String = when (step.status) {
        AgentStepStatus.WAITING -> "Menunggu"
        AgentStepStatus.RUNNING -> "Berjalan"
        AgentStepStatus.SUCCESS -> "Selesai ${formatSeconds(step.durationMs)}"
        AgentStepStatus.FAILED -> "Gagal ${formatSeconds(step.durationMs)}"
    }

    private fun formatSeconds(ms: Long): String = if (ms < 1000) "<1s" else "${ms / 1000}s"

    private fun statusColor(status: AgentStepStatus): Int = when (status) {
        AgentStepStatus.WAITING -> Palette.AMBER
        AgentStepStatus.RUNNING -> Palette.BRAND
        AgentStepStatus.SUCCESS -> Palette.GREEN
        AgentStepStatus.FAILED -> Palette.RED
    }

    companion object {
        fun preview(): List<AgentStepGroup> = listOf(
            AgentStepGroup(
                "Menjalankan perintah",
                "Membaca 2 file",
                listOf(
                    AgentStep("preview-1", AgentStepType.READ_FILE, "Membaca struktur proyek", "ls -la", AgentStepStatus.SUCCESS, 0L, 19000, "app/ app.gradle"),
                    AgentStep("preview-2", AgentStepType.COMMAND, "Menjalankan pemeriksaan", "./gradlew testDebugUnitTest", AgentStepStatus.RUNNING, 0L, 0L, "> Task :app:testDebugUnitTest")
                )
            )
        )
    }
}
