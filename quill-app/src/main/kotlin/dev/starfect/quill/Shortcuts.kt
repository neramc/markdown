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
import dev.starfect.quill.editing.MarkdownEdits
import dev.starfect.quill.model.Dialog
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.search.ProjectSearch

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
                workspace.projectSearch.visible -> {
                    controller.hideProjectSearch()
                    true
                }
                workspace.featurePaletteVisible -> {
                    controller.setFeaturePaletteVisible(false)
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
                // Last, because a mode that hides the interface must not be the first thing Escape
                // closes -- but it does have to be something Escape closes.
                workspace.settings.focusMode -> {
                    controller.toggleFocusMode()
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

    // The writing actions. Bindings follow the ones a writer already has in their fingers from every
    // other editor — Ctrl+B, Ctrl+I — and the IDE's own for the structural ones.
    if (activeId != null) {
        val handled = when {
            event.key == Key.B && !event.isShiftPressed && !event.isAltPressed -> {
                controller.edit { MarkdownEdits.toggleEmphasis(it, "**") }
                true
            }
            event.key == Key.I && !event.isShiftPressed && !event.isAltPressed -> {
                controller.edit { MarkdownEdits.toggleEmphasis(it, "*") }
                true
            }
            // Undo and redo, bound here rather than left to the text field.
            //
            // Compose's field carries an undo stack of its own, but it belongs to the composable:
            // switching tabs disposes it, so the history of what you were writing was thrown away
            // by looking at something else and coming back. It also has no redo. Both now live on
            // the document, in the controller.
            //
            // Ctrl+Shift+Z and Ctrl+Y both redo, because half the world learned each.
            event.key == Key.Z && !event.isShiftPressed -> {
                controller.activeDocumentId()?.let(controller::undo)
                true
            }
            event.key == Key.Z && event.isShiftPressed -> {
                controller.activeDocumentId()?.let(controller::redo)
                true
            }
            event.key == Key.Y && !event.isShiftPressed -> {
                controller.activeDocumentId()?.let(controller::redo)
                true
            }
            // Ctrl+Shift+C rather than Ctrl+C, which is copy and always will be.
            event.key == Key.C && event.isShiftPressed -> {
                controller.edit { MarkdownEdits.toggleEmphasis(it, "`") }
                true
            }
            // Ctrl+Shift+K inserts a link. Ctrl+K, which most Markdown editors use for it, is
            // spent on the feature search below -- a better use of the most reachable key in the
            // editor, since the search is also where somebody finds the link action.
            event.key == Key.K && event.isShiftPressed -> {
                controller.edit { MarkdownEdits.insertLink(it) }
                true
            }
            // Alt+Shift+arrow moves lines, as it does in the IDE.
            event.isAltPressed && event.isShiftPressed && event.key == Key.DirectionUp -> {
                controller.edit { MarkdownEdits.moveLines(it, -1) }
                true
            }
            event.isAltPressed && event.isShiftPressed && event.key == Key.DirectionDown -> {
                controller.edit { MarkdownEdits.moveLines(it, 1) }
                true
            }
            event.key == Key.D && !event.isShiftPressed -> {
                controller.edit { MarkdownEdits.duplicateLines(it) }
                true
            }
            // Ctrl+Alt+L formats, matching the IDE's reformat binding.
            event.isAltPressed && event.key == Key.L -> {
                controller.edit { MarkdownEdits.formatTable(it) }
                true
            }
            event.isShiftPressed && event.key == Key.Period -> {
                controller.edit { MarkdownEdits.shiftHeading(it, 1) }
                true
            }
            event.isShiftPressed && event.key == Key.Comma -> {
                controller.edit { MarkdownEdits.shiftHeading(it, -1) }
                true
            }
            event.isShiftPressed && event.key == Key.L -> {
                controller.edit { MarkdownEdits.toggleBullet(it) }
                true
            }
            event.isShiftPressed && event.key == Key.T -> {
                controller.edit { MarkdownEdits.toggleTask(it) }
                true
            }
            event.isShiftPressed && event.key == Key.Q -> {
                controller.edit { MarkdownEdits.toggleQuote(it) }
                true
            }
            else -> false
        }
        if (handled) return true
    }

    return when {
        // Ctrl+V is intercepted so a paste goes through the converter rather than through the
        // text field, which would insert the clipboard's plain-text flavour and lose the structure.
        event.key == Key.V && !event.isShiftPressed && !event.isAltPressed -> {
            controller.pasteClean()
            true
        }
        // Ctrl+Shift+V pastes exactly what is on the clipboard, for when the conversion is not
        // wanted -- copying a Markdown source out of a rendered page, most often.
        event.key == Key.V && event.isShiftPressed -> false
        event.key == Key.K && !event.isShiftPressed -> {
            controller.setFeaturePaletteVisible(!controller.state.value.featurePaletteVisible)
            true
        }
        event.key == Key.N && !event.isShiftPressed -> {
            controller.newDocument()
            true
        }
        event.key == Key.S && !event.isShiftPressed -> {
            activeId?.let(controller::saveWithPrompt)
            true
        }
        event.key == Key.S && event.isShiftPressed -> {
            activeId?.let(controller::saveAs)
            true
        }
        event.key == Key.W -> {
            activeId?.let(controller::requestCloseDocument)
            true
        }
        event.key == Key.F && !event.isShiftPressed -> {
            controller.setFindVisible(true)
            true
        }
        // Ctrl+Shift+F searches the project rather than the document, as it does in the IDE.
        event.key == Key.F && event.isShiftPressed -> {
            controller.showProjectSearch(ProjectSearch.Scope.CONTENT)
            true
        }
        // Ctrl+Shift+N goes to a file by name; the other three scopes are a click away from there.
        event.key == Key.N && event.isShiftPressed -> {
            controller.showProjectSearch(ProjectSearch.Scope.FILE_NAMES)
            true
        }
        // Ctrl+Shift+O lists what was left unfinished, which nothing else in the editor can show.
        event.key == Key.O && event.isShiftPressed -> {
            controller.showProjectSearch(ProjectSearch.Scope.TODO)
            true
        }
        // Ctrl+E is the IDE's recent-files switcher.
        event.key == Key.E && !event.isShiftPressed -> {
            controller.showProjectSearch(ProjectSearch.Scope.RECENT)
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
        // Ctrl+Shift+M switches between writing and reading, which is the question people have.
        event.key == Key.M && event.isShiftPressed -> {
            controller.toggleReadingMode()
            true
        }
        // Ctrl+Shift+D is distraction-free writing.
        event.key == Key.D && event.isShiftPressed -> {
            controller.toggleFocusMode()
            true
        }
        event.key == Key.Six && event.isShiftPressed -> {
            controller.setBottomToolWindow(ToolWindow.PROBLEMS)
            true
        }
        else -> false
    }
}
