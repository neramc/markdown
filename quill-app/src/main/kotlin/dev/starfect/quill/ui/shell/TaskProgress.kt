package dev.starfect.quill.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.BackgroundTask
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Motion
import dev.starfect.quill.ui.theme.Tokens
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * What Quill is doing, in the status bar.
 *
 * The IDE's own arrangement: a label, a thin bar, and a way to stop it. It sits at the left of the
 * right-hand status group, which is where every JetBrains window puts it, so the eye already knows
 * to look there.
 *
 * ## The timing rules are the feature
 *
 * A naive indicator that mirrors task state exactly is worse than none: background work fires
 * constantly, most of it finishes in milliseconds, and a bar that appears and vanishes on every
 * keystroke reads as an application in distress. Two rules fix it, and both are about *time* rather
 * than about progress:
 *
 *  - **Nothing is shown for the first fifth of a second.** Work that finishes inside that never
 *    existed as far as the window is concerned.
 *  - **Once shown, it stays for at least four tenths.** Without this, a task that crosses the
 *    threshold and finishes immediately after produces a one-frame flash, which reads as a glitch.
 *
 * ## More than one
 *
 * The newest task is named and the rest are counted, because the status bar has room for one line
 * and a reader looking at it wants to know what is happening *now*. Clicking opens the full list,
 * where each can be stopped on its own.
 */
@Composable
internal fun TaskProgress(controller: QuillController, tasks: List<BackgroundTask>) {
    val shell = LocalShellPalette.current
    val type = LocalTypeScale.current

    // Which task the bar is describing, held across the minimum-visible window so that a task
    // finishing does not blank the label before the bar has gone.
    var shown by remember { mutableStateOf<BackgroundTask?>(null) }
    var extra by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf(false) }

    val newest = tasks.lastOrNull()

    LaunchedEffect(newest?.id, tasks.size) {
        if (newest == null) {
            // Hold what was on screen for the rest of its minimum, then clear.
            if (shown != null) {
                delay(BackgroundTask.MINIMUM_VISIBLE_MILLIS)
                shown = null
                extra = 0
                open = false
            }
            return@LaunchedEffect
        }

        extra = tasks.size - 1
        if (shown == null) {
            // Not yet shown: wait out the threshold, and only appear if it is still running.
            delay(BackgroundTask.SHOW_AFTER_MILLIS)
        }
        shown = newest
    }

    // Progress and detail change on the same task without changing its identity, so they are read
    // fresh rather than from the remembered snapshot.
    val live = shown?.let { held -> tasks.firstOrNull { it.id == held.id } ?: held }

    AnimatedVisibility(visible = live != null, enter = Motion.barEnter, exit = Motion.barExit) {
        val task = live ?: return@AnimatedVisibility

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Tiny),
            modifier = Modifier.padding(end = Tokens.Spacing.Small),
        ) {
            Box {
                // Clickable only when there is a list worth opening. One task is already fully
                // described by the label beside the bar.
                IdeWidgetButton(onClick = if (tasks.size > 1) ({ open = !open }) else null) {
                    Text(
                        text = buildString {
                            append(task.title)
                            if (task.stopping) append(" — stopping") else append('…')
                            if (extra > 0) append("  +$extra")
                        },
                        color = shell.mutedText,
                        fontSize = type.medium,
                        maxLines = 1,
                    )
                }

                if (open) {
                    PopupMenu(
                        onDismissRequest = {
                            open = false
                            true
                        },
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.width(320.dp),
                    ) {
                        tasks.reversed().forEach { entry ->
                            selectableItem(
                                selected = false,
                                enabled = entry.cancellable && !entry.stopping,
                                onClick = {
                                    open = false
                                    controller.cancelTask(entry.id)
                                },
                            ) {
                                Column {
                                    Text(if (entry.stopping) "${entry.title} — stopping" else entry.title)
                                    entry.detail?.let {
                                        Text(it, color = shell.mutedText, fontSize = type.medium, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // The bar. Determinate when the work knows where it ends, indeterminate when it does
            // not — a bar that crawls to 90% and waits is a worse lie than one that says nothing.
            Box(Modifier.width(ProgressWidth).height(ProgressHeight)) {
                val fraction = task.fraction
                if (fraction == null) {
                    IndeterminateHorizontalProgressBar(Modifier.fillMaxWidth().height(ProgressHeight))
                } else {
                    // Animated so a jump from 0.2 to 0.9 travels rather than teleports, which is
                    // what makes a fast task look like progress instead of like a redraw.
                    val eased by animateFloatAsState(fraction, Motion.state(), label = "taskProgress")
                    HorizontalProgressBar(eased, Modifier.fillMaxWidth().height(ProgressHeight))
                }
            }

            if (task.cancellable) {
                Tooltip(tooltip = { Text(if (task.stopping) "Stopping" else "Stop") }) {
                    IdeActionButton(
                        onClick = { controller.cancelTask(task.id) },
                        tooltip = if (task.stopping) "Stopping" else "Stop",
                        enabled = !task.stopping,
                        size = Tokens.SmallControlSize,
                    ) { tint -> IdeIcons.Close(tint, size = Tokens.SmallIconSize) }
                }
            } else {
                Spacer(Modifier.width(Tokens.Spacing.Tiny))
            }
        }
    }
}

private val ProgressWidth = 92.dp
private val ProgressHeight = 3.dp
