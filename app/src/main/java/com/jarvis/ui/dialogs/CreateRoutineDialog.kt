package com.jarvis.ui.dialogs

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.jarvis.macro.MacroActionType
import com.jarvis.macro.MacroStep
import com.jarvis.macro.UiMacro
import com.jarvis.ui.components.Ui
import java.util.UUID

/**
 * Material 3 holographic dialog for constructing custom voice routines and multi-step UI macros.
 */
class CreateRoutineDialog(
    private val context: Context,
    private val existingMacro: UiMacro? = null,
    private val onSaved: (UiMacro) -> Unit
) {

    private var alertDialog: AlertDialog? = null
    private val steps = mutableListOf<MacroStep>()

    init {
        existingMacro?.let {
            steps.addAll(it.steps)
        }
    }

    fun show() {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE_CONTAINER)
            setPadding(Ui.dp(context, 18), Ui.dp(context, 16), Ui.dp(context, 18), Ui.dp(context, 20))
        }
        scroll.addView(root)

        // Title Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Ui.dp(context, 12))
        }
        header.addView(TextView(context).apply {
            text = if (existingMacro != null) "EDIT VOICE ROUTINE" else "CREATE VOICE ROUTINE"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.1f
        })
        header.addView(TextView(context).apply {
            text = "Chain actions triggered by custom natural voice commands."
            textSize = 11f
            setTextColor(Ui.MUTED)
        })
        root.addView(header)

        // Name input
        val nameInput = Ui.input(context, "Routine Name (e.g. Work Mode)")
        nameInput.second.setText(existingMacro?.name.orEmpty())
        root.addView(nameInput.first)

        // Trigger input
        val triggerInput = Ui.input(context, "Voice Triggers (comma separated, e.g. work mode, start work)")
        triggerInput.second.setText(existingMacro?.triggers?.joinToString(", ").orEmpty())
        root.addView(triggerInput.first)

        // Description input
        val descInput = Ui.input(context, "Description (optional)")
        descInput.second.setText(existingMacro?.description.orEmpty())
        root.addView(descInput.first)

        root.addView(Ui.divider(context))

        // Steps container
        root.addView(Ui.sectionLabel(context, "Sequence Steps (${steps.size})"))
        val stepsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(stepsContainer)

        fun refreshSteps() {
            stepsContainer.removeAllViews()
            if (steps.isEmpty()) {
                stepsContainer.addView(TextView(context).apply {
                    text = "No steps added yet. Use the builder below to add steps."
                    textSize = 12f
                    setTextColor(Ui.MUTED)
                    setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 8))
                })
            } else {
                steps.forEachIndexed { index, step ->
                    val row = Ui.glassCard(context, Ui.BORDER).apply {
                        setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
                    }
                    val rowInner = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val info = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    info.addView(TextView(context).apply {
                        text = "${index + 1}. [${step.actionType.name}] ${step.description.ifBlank { step.target }}"
                        textSize = 12.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Ui.TEXT)
                    })
                    if (step.payload.isNotBlank()) {
                        info.addView(TextView(context).apply {
                            text = "Payload: ${step.payload}"
                            textSize = 10.5f
                            setTextColor(Ui.MUTED)
                        })
                    }
                    rowInner.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

                    val delBtn = Ui.dangerButton(context, "✕") {
                        steps.removeAt(index)
                        refreshSteps()
                    }
                    rowInner.addView(delBtn)
                    row.addView(rowInner)
                    stepsContainer.addView(row)
                }
            }
        }
        refreshSteps()

        // Step Builder Card
        root.addView(Ui.sectionLabel(context, "+ Add Step to Sequence"))
        val addCard = Ui.glassCard(context, Ui.BORDER_GLOW).apply {
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        }
        val addInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val typeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("GLOBAL_ACTION", "LAUNCH_APP", "CLICK_TEXT", "TYPE_TEXT", "DELAY")
            )
        }
        addInner.addView(TextView(context).apply {
            text = "Action Type"
            textSize = 11f
            setTextColor(Ui.MUTED)
        })
        addInner.addView(typeSpinner)

        val targetInput = Ui.input(context, "Target (e.g. home, recents, Close all, etc.)")
        val payloadInput = Ui.input(context, "Payload (e.g. delay ms, package name, text to type)")
        addInner.addView(targetInput.first)
        addInner.addView(payloadInput.first)

        val addStepBtn = Ui.button(context, "+ APPEND STEP") {
            val selectedType = try {
                MacroActionType.valueOf(typeSpinner.selectedItem.toString())
            } catch (_: Exception) {
                MacroActionType.GLOBAL_ACTION
            }
            val t = targetInput.second.text.toString().trim()
            val p = payloadInput.second.text.toString().trim()
            val stepDesc = when (selectedType) {
                MacroActionType.GLOBAL_ACTION -> "Global action '$t'"
                MacroActionType.LAUNCH_APP -> "Launch app '$p'"
                MacroActionType.CLICK_TEXT -> "Click text '$t'"
                MacroActionType.TYPE_TEXT -> "Type '$p'"
                MacroActionType.DELAY -> "Wait ${p}ms"
                else -> selectedType.name
            }

            steps.add(
                MacroStep(
                    stepIndex = steps.size + 1,
                    actionType = selectedType,
                    target = t,
                    payload = p,
                    description = stepDesc
                )
            )
            targetInput.second.text?.clear()
            payloadInput.second.text?.clear()
            refreshSteps()
        }
        addInner.addView(addStepBtn)
        addCard.addView(addInner)
        root.addView(addCard)

        // Bottom Actions
        val actionRow = Ui.row(context).apply {
            setPadding(0, Ui.dp(context, 16), 0, 0)
        }
        actionRow.addView(Ui.button(context, "CANCEL") {
            alertDialog?.dismiss()
        })
        actionRow.addView(Ui.primaryButton(context, "SAVE ROUTINE") {
            val name = nameInput.second.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(context, "Please enter a routine name", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            if (steps.isEmpty()) {
                Toast.makeText(context, "Please add at least one step", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            val rawTriggers = triggerInput.second.text.toString()
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }

            val triggers = if (rawTriggers.isEmpty()) listOf(name.lowercase()) else rawTriggers

            val macroId = existingMacro?.id ?: ("routine_" + UUID.randomUUID().toString().take(8))
            val macro = UiMacro(
                id = macroId,
                name = name,
                description = descInput.second.text.toString().trim(),
                steps = steps.toList(),
                triggers = triggers
            )

            onSaved(macro)
            alertDialog?.dismiss()
        })
        root.addView(actionRow)

        alertDialog = AlertDialog.Builder(context)
            .setView(scroll)
            .create()
            .apply { show() }
    }
}
