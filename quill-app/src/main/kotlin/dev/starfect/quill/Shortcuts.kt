package dev.starfect.quill

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.starfect.quill.model.Dialog
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.ViewMode

/**
 * Window-level keyboard shortcuts, using the IDE's own bindings.
 *
 * Returning `true` consumes the event, so anything not handled here still reaches the editor.
 */
internal fun handleShortcut(event: KeyEvent, controller: QuillController): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    // Cmd on macOS, Ctrl elsewhere.
    val primary = event.isCtrlPressed || event.isMetaPressed
    if (!primary) {
        val workspace = controller.state.value
        return when {
            event.key == Key.Escape -> when {
                // Innermost first: a dialog over a palette over a find bar, closed in that order.
                workspace.dialog != null -> {
                    controller.dismissDialog()
                    true
                }
                workspace.commandPaletteVisible -> {
                    controller.setCommandPaletteVisible(false)
                    true
                }
                workspace.find.visible -> {
                    controller.setFindVisible(false)
                    true
                }
                else -> false
            }

            // Shift+F10 runs, as it does in the IDE.
            event.key == Key.F10 && event.isShiftPressed -> {
                workspace.activeRunConfiguration
                    ?.let(controller::run)
                    ?: controller.showDialog(Dialog.RUN_CONFIGURATIONS)
                true
            }

            // F2 and Shift+F2 step through the inspection findings.
            event.key == Key.F2 -> {
                workspace.activeDocument?.let { controller.goToFinding(it, forward = !event.isShiftPressed) }
                true
            }

            else -> false
        }
    }

    // Ctrl+Alt+S opens Settings, which is the IDE's binding and not one to reassign.
    if (event.isAltPressed && event.key == Key.S) {
        controller.showDialog(Dialog.SETTINGS)
        return true
    }

    val activeId = controller.state.value.activeDocumentId

    return when {
        event.key == Key.N && !event.isShiftPressed -> {
            controller.newDocument()
            true
        }
        event.key == Key.S && !event.isShiftPressed -> {
            activeId?.let { controller.save(it) { null } }
            true
        }
        event.key == Key.W -> {
            activeId?.let(controller::closeDocument)
            true
        }
        event.key == Key.F && !event.isShiftPressed -> {
            controller.setFindVisible(true)
            true
        }
        event.key == Key.R && !event.isShiftPressed -> {
            controller.setFindVisible(visible = true, withReplace = true)
            true
        }
        event.key == Key.P && event.isShiftPressed -> {
            controller.setCommandPaletteVisible(!controller.state.value.commandPaletteVisible)
            true
        }
        event.key == Key.G -> {
            controller.stepMatch(forward = !event.isShiftPressed)
            true
        }
        event.key == Key.One -> {
            controller.setViewMode(ViewMode.EDITOR)
            true
        }
        event.key == Key.Two -> {
            controller.setViewMode(ViewMode.SPLIT)
            true
        }
        event.key == Key.Three -> {
            controller.setViewMode(ViewMode.PREVIEW)
            true
        }
        event.key == Key.T && event.isShiftPressed -> {
            controller.toggleTheme()
            true
        }
        event.key == Key.Six && event.isShiftPressed -> {
            controller.setBottomToolWindow(ToolWindow.PROBLEMS)
            true
        }
        else -> false
    }
}
