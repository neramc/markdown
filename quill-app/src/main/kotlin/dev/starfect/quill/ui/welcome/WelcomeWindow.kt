package dev.starfect.quill.ui.welcome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.starfect.quill.io.RecentProject
import dev.starfect.quill.model.Keymap
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.shell.IdeActionButton
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Motion
import dev.starfect.quill.ui.theme.ShellDivider
import dev.starfect.quill.ui.theme.ShellPalette
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.interactiveSurface
import java.nio.file.Path
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.MenuSeparator
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** What the welcome window's left rail can show. */
internal enum class WelcomePage(val title: String) {
    Projects("Projects"),
    Learn("Learn"),
    Customize("Customize"),
}

/**
 * Below this width the header's action buttons fold into a single overflow button.
 *
 * The number comes from the layout rather than from a guess: the search field stops being usable
 * at roughly a third of the pane, and two buttons plus their spacing take about 200 points. It is
 * the same behaviour the IDE's own welcome screen has — narrow the window and the buttons go, not
 * the search.
 */
private val ActionsCollapseBelow: Dp = 560.dp

/**
 * The welcome window, shown when Quill starts with no project.
 *
 * A narrow rail carrying the product identity, a short navigation list and — pinned to the bottom —
 * the two menus that belong to the application rather than to any project. Beside it, a content
 * pane that is either a grid of large actions when there is nothing to reopen or a searchable list
 * of recent projects when there is.
 *
 * The pane is deliberately responsive. Everything in the header has somewhere to go when the window
 * is narrowed: the buttons collapse into an overflow, the search field keeps its width, and nothing
 * is clipped or pushed off the edge. A welcome screen is the first thing anybody resizes.
 */
@Composable
public fun WelcomeContent(
    version: String,
    recents: List<RecentProject>,
    onOpenProject: (Path) -> Unit,
    onNewDocument: () -> Unit,
    onBrowse: () -> Unit,
    onForget: (Path) -> Unit,
    onToggleTheme: () -> Unit,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    /**
     * What the rail's Configure and Help menus can reach.
     *
     * Null means the entry is not shown at all rather than shown and inert. The render tests mount
     * this window without a controller, and a menu that offered Settings there would open nothing.
     */
    onSettings: (() -> Unit)? = null,
    onAbout: (() -> Unit)? = null,
    onCheckForUpdates: (() -> Unit)? = null,
) {
    val shell = LocalShellPalette.current
    var page by remember { mutableStateOf(WelcomePage.Projects) }
    var query by remember { mutableStateOf(TextFieldValue("")) }

    val filtered = remember(recents, query.text) {
        if (query.text.isBlank()) {
            recents
        } else {
            val needle = query.text.trim().lowercase()
            recents.filter {
                it.name.lowercase().contains(needle) || it.displayPath.lowercase().contains(needle)
            }
        }
    }

    Row(modifier.fillMaxSize().background(shell.welcomeBackground)) {
        WelcomeRail(
            version = version,
            selected = page,
            onSelect = { page = it },
            onSettings = onSettings,
            onAbout = onAbout,
            onCheckForUpdates = onCheckForUpdates,
            darkTheme = darkTheme,
            onToggleTheme = onToggleTheme,
        )
        ShellDivider(Orientation.Vertical)

        Box(Modifier.weight(1f).fillMaxHeight()) {
            // Cross-fade rather than slide. The panes have nothing in common to carry across, and a
            // pane that travels makes the rail look like it scrolled something.
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    fadeIn(tween(Motion.ENTER_MILLIS, easing = Motion.Easing)) togetherWith
                        fadeOut(tween(Motion.EXIT_MILLIS, easing = Motion.Easing))
                },
                label = "welcomePage",
            ) { current ->
                when (current) {
                    WelcomePage.Projects ->
                        if (recents.isEmpty()) {
                            EmptyProjects(onNewDocument, onBrowse)
                        } else {
                            RecentProjectsPane(
                                query = query,
                                onQueryChange = { query = it },
                                projects = filtered,
                                onOpenProject = onOpenProject,
                                onNewDocument = onNewDocument,
                                onBrowse = onBrowse,
                                onForget = onForget,
                            )
                        }

                    WelcomePage.Learn -> LearnPane()
                    WelcomePage.Customize -> CustomizePane(darkTheme, onToggleTheme)
                }
            }
        }
    }
}

/** The left rail: product identity, navigation, and the application menus pinned to the bottom. */
@Composable
private fun WelcomeRail(
    version: String,
    selected: WelcomePage,
    onSelect: (WelcomePage) -> Unit,
    onSettings: (() -> Unit)?,
    onAbout: (() -> Unit)?,
    onCheckForUpdates: (() -> Unit)?,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val shell = LocalShellPalette.current

    Column(
        modifier = Modifier.width(Tokens.WelcomeRailWidth).fillMaxHeight()
            .background(shell.toolWindowBackground)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource("icons/icon.png"),
                contentDescription = null,
                modifier = Modifier.size(38.dp),
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text("Quill", fontSize = LocalTypeScale.current.h2, fontWeight = FontWeight.SemiBold, color = shell.text)
                Text(version, fontSize = LocalTypeScale.current.medium, color = shell.mutedText)
            }
        }

        WelcomePage.entries.forEach { entry ->
            RailItem(entry.title, entry == selected) { onSelect(entry) }
        }

        Spacer(Modifier.weight(1f))

        // Configure and Help, as the IDE's welcome screen has them: text rows with a chevron,
        // pinned to the bottom, holding what belongs to the application rather than to a project.
        RailMenu("Configure") { dismiss ->
            if (onSettings != null) {
                selectableItem(
                    selected = false,
                    onClick = {
                        dismiss()
                        onSettings()
                    },
                ) { Text("Settings…") }
            }
            selectableItem(
                selected = false,
                onClick = {
                    dismiss()
                    onToggleTheme()
                },
            ) { Text(if (darkTheme) "Switch to Light Theme" else "Switch to Dark Theme") }
        }

        RailMenu("Help") { dismiss ->
            if (onCheckForUpdates != null) {
                selectableItem(
                    selected = false,
                    onClick = {
                        dismiss()
                        onCheckForUpdates()
                    },
                ) { Text("Check for Updates…") }
            }
            selectableItem(
                selected = false,
                onClick = {
                    dismiss()
                    onSelect(WelcomePage.Learn)
                },
            ) { Text("Keyboard Shortcuts") }

            if (onAbout != null) {
                passiveItem { MenuSeparator() }
                selectableItem(
                    selected = false,
                    onClick = {
                        dismiss()
                        onAbout()
                    },
                ) { Text("About Quill") }
            }
        }
    }
}

@Composable
private fun RailItem(title: String, selected: Boolean, onClick: () -> Unit) {
    val shell = LocalShellPalette.current

    Row(
        modifier = Modifier.fillMaxWidth().height(Tokens.MenuRowHeight)
            .interactiveSurface(
                onClick = onClick,
                palette = shell,
                selected = selected,
                cornerRadius = Tokens.Radius.Control,
            )
            .padding(horizontal = Tokens.Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = LocalTypeScale.current.default, color = shell.text, maxLines = 1)
    }
}

/** One of the bottom-of-rail menus: a label, a chevron, and a popup. */
@Composable
private fun RailMenu(
    title: String,
    content: MenuScope.(dismiss: () -> Unit) -> Unit,
) {
    val shell = LocalShellPalette.current
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier.fillMaxWidth().height(Tokens.MenuRowHeight)
                .interactiveSurface(
                    onClick = { open = !open },
                    palette = shell,
                    selected = open,
                    cornerRadius = Tokens.Radius.Control,
                )
                .padding(horizontal = Tokens.Spacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = LocalTypeScale.current.default, color = shell.secondaryText, maxLines = 1)
            Spacer(Modifier.width(Tokens.Spacing.Tiny))
            IdeIcons.WidgetChevron(shell.mutedText)
        }

        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.width(240.dp),
            ) {
                content { open = false }
            }
        }
    }
}

/**
 * The empty state: a headline and a row of large square actions.
 *
 * This is the layout IntelliJ shows a first-time user, and its proportions are deliberate — the
 * actions are large squares with the icon above the label, not a row of ordinary buttons.
 */
@Composable
private fun EmptyProjects(onNewDocument: () -> Unit, onBrowse: () -> Unit) {
    val shell = LocalShellPalette.current

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Welcome to Quill", fontSize = LocalTypeScale.current.h1, fontWeight = FontWeight.SemiBold, color = shell.text)
        Text(
            text = "Create a document to start from scratch.",
            fontSize = LocalTypeScale.current.default,
            color = shell.mutedText,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "Open a folder from disk to work on an existing set of notes.",
            fontSize = LocalTypeScale.current.default,
            color = shell.mutedText,
            modifier = Modifier.padding(top = 2.dp, bottom = 32.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigAction("New Document", primary = true, onClick = onNewDocument) { tint ->
                IdeIcons.Plus(tint, size = Tokens.LargeIconSize)
            }
            BigAction("Open", primary = false, onClick = onBrowse) { tint ->
                IdeIcons.OpenFolder(tint, size = Tokens.LargeIconSize)
            }
        }
    }
}

@Composable
private fun BigAction(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // The primary action carries the accent outline, which is how the IDE marks "New Project" as the
    // one most people want without making it a filled button.
    val target = when {
        primary -> shell.accent
        hovered -> shell.mutedText
        else -> shell.border
    }
    val borderColor by animateColorAsState(target, Motion.state(), label = "bigActionBorder")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(Tokens.WelcomeActionSize)
                .border(1.dp, borderColor, RoundedCornerShape(Tokens.Radius.Control))
                .interactiveSurface(
                    onClick = onClick,
                    palette = shell,
                    cornerRadius = Tokens.Radius.Control,
                    interactionSource = interaction,
                ),
            contentAlignment = Alignment.Center,
        ) {
            icon(if (primary) shell.accent else shell.icon)
        }
        Text(
            text = label,
            fontSize = LocalTypeScale.current.default,
            color = shell.text,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * The populated state: a search field and the primary actions across the top, then the list.
 *
 * Each row carries the coloured avatar the IDE gives every project, the name, and the abbreviated
 * location underneath — which is what lets someone pick out the right one of six checkouts that all
 * share a name.
 */
@Composable
private fun RecentProjectsPane(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    projects: List<RecentProject>,
    onOpenProject: (Path) -> Unit,
    onNewDocument: () -> Unit,
    onBrowse: () -> Unit,
    onForget: (Path) -> Unit,
) {
    val shell = LocalShellPalette.current
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            val roomForButtons = maxWidth >= ActionsCollapseBelow

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search projects", color = shell.mutedText) },
                        leadingIcon = {
                            Box(Modifier.padding(start = 6.dp)) {
                                IdeIcons.Search(shell.mutedText, size = Tokens.IconSize)
                            }
                        },
                    )
                }

                // The buttons fade rather than blink, and the overflow fades in as they leave, so a
                // drag-resize does not look like the header is being rebuilt each frame.
                AnimatedVisibility(
                    visible = roomForButtons,
                    enter = fadeIn(tween(Motion.ENTER_MILLIS, easing = Motion.Easing)),
                    exit = fadeOut(tween(Motion.EXIT_MILLIS, easing = Motion.Easing)),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WelcomeButton("New Document", onNewDocument)
                        WelcomeButton("Open", onBrowse)
                    }
                }

                AnimatedVisibility(
                    visible = !roomForButtons,
                    enter = fadeIn(tween(Motion.ENTER_MILLIS, easing = Motion.Easing)),
                    exit = fadeOut(tween(Motion.EXIT_MILLIS, easing = Motion.Easing)),
                ) {
                    HeaderOverflow(onNewDocument, onBrowse)
                }
            }
        }

        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No projects match", color = shell.mutedText, fontSize = LocalTypeScale.current.medium)
            }
            return@Column
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(projects.size, key = { projects[it].path.toString() }) { index ->
                RecentProjectRow(projects[index], onOpenProject, onForget)
            }
        }
    }
}

/** Where the header's actions go when the window is too narrow to spell them out. */
@Composable
private fun HeaderOverflow(onNewDocument: () -> Unit, onBrowse: () -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        IdeActionButton(
            onClick = { open = !open },
            tooltip = "More Actions",
            selected = open,
            size = Tokens.MenuRowHeight,
        ) { tint -> IdeIcons.MoreVertical(tint) }

        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.End,
                modifier = Modifier.width(220.dp),
            ) {
                selectableItem(
                    selected = false,
                    onClick = {
                        open = false
                        onNewDocument()
                    },
                ) { Text("New Document") }

                selectableItem(
                    selected = false,
                    onClick = {
                        open = false
                        onBrowse()
                    },
                ) { Text("Open…") }
            }
        }
    }
}

@Composable
private fun RecentProjectRow(
    project: RecentProject,
    onOpen: (Path) -> Unit,
    onForget: (Path) -> Unit,
) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().height(Tokens.WelcomeRecentRowHeight)
            .interactiveSurface(
                onClick = { onOpen(project.path) },
                palette = shell,
                cornerRadius = Tokens.Radius.Control,
                interactionSource = interaction,
            )
            .padding(horizontal = Tokens.Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(Tokens.ToolWindowBarWidth)
                .clip(RoundedCornerShape(Tokens.ProjectBadgeCorner))
                .background(ShellPalette.badgeColor(project.name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = project.name.take(2).uppercase(),
                fontSize = LocalTypeScale.current.medium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
            )
        }

        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(project.name, fontSize = LocalTypeScale.current.default, color = shell.text, maxLines = 1)
            Text(
                text = project.displayPath,
                fontSize = LocalTypeScale.current.medium,
                color = shell.mutedText,
                maxLines = 1,
            )
        }

        // The row's own menu, on hover only, exactly as the IDE's list does it. It replaced a bare
        // close button: removing an entry is not the only thing anyone wants to do to a row, and a
        // lone X next to a project reads as "delete this project".
        AnimatedVisibility(
            visible = hovered || menuOpen,
            enter = fadeIn(tween(Motion.STATE_MILLIS, easing = Motion.Easing)),
            exit = fadeOut(tween(Motion.STATE_MILLIS, easing = Motion.Easing)),
        ) {
            Box {
                IdeActionButton(
                    onClick = { menuOpen = !menuOpen },
                    tooltip = project.name,
                    selected = menuOpen,
                    size = Tokens.SmallControlSize,
                ) { tint -> IdeIcons.MoreVertical(tint, size = Tokens.SmallIconSize) }

                if (menuOpen) {
                    PopupMenu(
                        onDismissRequest = {
                            menuOpen = false
                            true
                        },
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.width(260.dp),
                    ) {
                        selectableItem(
                            selected = false,
                            onClick = {
                                menuOpen = false
                                onOpen(project.path)
                            },
                        ) { Text("Open") }

                        selectableItem(
                            selected = false,
                            onClick = {
                                menuOpen = false
                                copyToClipboard(project.path.toString())
                            },
                        ) { Text("Copy Path") }

                        passiveItem { MenuSeparator() }

                        selectableItem(
                            selected = false,
                            onClick = {
                                menuOpen = false
                                onForget(project.path)
                            },
                        ) { Text("Remove from Recent Projects") }
                    }
                }
            }
        }
    }
}

/** Puts [text] on the system clipboard, ignoring a headless or locked one. */
private fun copyToClipboard(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }
}

@Composable
private fun WelcomeButton(label: String, onClick: () -> Unit) {
    val shell = LocalShellPalette.current

    Box(
        modifier = Modifier.height(Tokens.MenuRowHeight)
            .border(1.dp, shell.border, RoundedCornerShape(Tokens.Radius.Control))
            .interactiveSurface(
                onClick = onClick,
                palette = shell,
                cornerRadius = Tokens.Radius.Control,
            )
            .padding(horizontal = Tokens.Spacing.Medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = LocalTypeScale.current.medium, color = shell.text, maxLines = 1)
    }
}

/**
 * The keyboard reference.
 *
 * A welcome screen is where somebody is between having installed the thing and knowing how to use
 * it, and a Markdown editor's whole speed argument is that the writing actions are under the
 * fingers. Listing them here costs one pane and saves the search that most people never make.
 */
@Composable
private fun LearnPane() {
    val shell = LocalShellPalette.current
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text(
            text = "Keyboard shortcuts",
            fontSize = LocalTypeScale.current.h2,
            fontWeight = FontWeight.SemiBold,
            color = shell.text,
        )
        Text(
            text = "On macOS, use Cmd wherever this says Ctrl.",
            fontSize = LocalTypeScale.current.medium,
            color = shell.mutedText,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            Keymap.sections.forEach { section ->
                item(key = "section-${section.title}") {
                    Text(
                        text = section.title,
                        fontSize = LocalTypeScale.current.default,
                        fontWeight = LocalTypeScale.current.headerWeight,
                        color = shell.text,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                }

                items(section.bindings.size, key = { "${section.title}-$it" }) { index ->
                    val binding = section.bindings[index]
                    Row(
                        modifier = Modifier.fillMaxWidth().height(Tokens.TreeRowHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = binding.action,
                            fontSize = LocalTypeScale.current.default,
                            color = shell.secondaryText,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text(
                            text = binding.keys,
                            fontSize = LocalTypeScale.current.medium,
                            color = shell.mutedText,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The Customize page: the one setting the welcome window can meaningfully change before a project. */
@Composable
private fun CustomizePane(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    val shell = LocalShellPalette.current

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text("Customize", fontSize = LocalTypeScale.current.h2, fontWeight = FontWeight.SemiBold, color = shell.text)
        Text(
            text = "Colour theme",
            fontSize = LocalTypeScale.current.medium,
            color = shell.mutedText,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoice("Dark", darkTheme) { if (!darkTheme) onToggleTheme() }
            ThemeChoice("Light", !darkTheme) { if (darkTheme) onToggleTheme() }
        }
    }
}

@Composable
private fun ThemeChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val shell = LocalShellPalette.current
    val border by animateColorAsState(
        if (selected) shell.accent else shell.border,
        Motion.state(),
        label = "themeChoiceBorder",
    )
    val fill by animateColorAsState(
        if (selected) shell.selectionBackground else Color.Transparent,
        Motion.state(),
        label = "themeChoiceFill",
    )

    Box(
        modifier = Modifier.height(30.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(5.dp))
            .hoverable(remember { MutableInteractionSource() })
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = LocalTypeScale.current.default, color = shell.text, maxLines = 1)
    }
}
