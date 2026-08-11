package dev.starfect.quill.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.starfect.quill.io.RecentProject
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.shell.IdeActionButton
import dev.starfect.quill.ui.theme.IdeaMetrics
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellPalette
import java.nio.file.Path
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** What the welcome window's left rail can show. */
internal enum class WelcomePage(val title: String) {
    Projects("Projects"),
    Customize("Customize"),
}

/**
 * The welcome window, shown when Quill starts with no project.
 *
 * IntelliJ's own is a two-pane window: a narrow rail carrying the product identity and a short
 * navigation list, and a content pane that is either a grid of large actions when there is nothing
 * to reopen, or a searchable list of recent projects when there is. Quill follows the same rule,
 * because the empty and populated states genuinely want different layouts and the IDE's choice of
 * which to show is the right one.
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
        WelcomeRail(version, page, onSelect = { page = it }, onToggleTheme = onToggleTheme, darkTheme = darkTheme)
        Divider(Orientation.Vertical, color = shell.border)

        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (page) {
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

                WelcomePage.Customize -> CustomizePane(darkTheme, onToggleTheme)
            }
        }
    }
}

/** The left rail: product identity, navigation, and a settings action pinned to the bottom. */
@Composable
private fun WelcomeRail(
    version: String,
    selected: WelcomePage,
    onSelect: (WelcomePage) -> Unit,
    onToggleTheme: () -> Unit,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val shell = LocalShellPalette.current

    Column(
        modifier = Modifier.width(IdeaMetrics.WelcomeRailWidth).fillMaxHeight()
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
                Text("Quill", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = shell.text)
                Text(version, fontSize = IdeaMetrics.SmallFontSize, color = shell.mutedText)
            }
        }

        WelcomePage.entries.forEach { entry ->
            RailItem(entry.title, entry == selected) { onSelect(entry) }
        }

        Box(Modifier.weight(1f))

        IdeActionButton(
            onClick = onToggleTheme,
            tooltip = if (darkTheme) "Switch to Light theme" else "Switch to Dark theme",
        ) { tint -> IdeIcons.Gear(tint) }
    }
}

@Composable
private fun RailItem(title: String, selected: Boolean, onClick: () -> Unit) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> shell.selectionBackground
                    hovered -> shell.hoverBackground
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = IdeaMetrics.UiFontSize, color = shell.text, maxLines = 1)
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
        Text("Welcome to Quill", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = shell.text)
        Text(
            text = "Create a document to start from scratch.",
            fontSize = IdeaMetrics.UiFontSize,
            color = shell.mutedText,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "Open a folder from disk to work on an existing set of notes.",
            fontSize = IdeaMetrics.UiFontSize,
            color = shell.mutedText,
            modifier = Modifier.padding(top = 2.dp, bottom = 32.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigAction("New Document", primary = true, onClick = onNewDocument) { tint ->
                IdeIcons.Plus(tint, size = 30.dp)
            }
            BigAction("Open", primary = false, onClick = onBrowse) { tint ->
                IdeIcons.OpenFolder(tint, size = 30.dp)
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
    val borderColor = when {
        primary -> shell.accent
        hovered -> shell.mutedText
        else -> shell.border
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(IdeaMetrics.WelcomeActionSize)
                .clip(RoundedCornerShape(10.dp))
                .background(if (hovered) shell.hoverBackground else Color.Transparent)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            icon(if (primary) shell.accent else shell.icon)
        }
        Text(
            text = label,
            fontSize = IdeaMetrics.UiFontSize,
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
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
                        Box(Modifier.padding(start = 6.dp)) { IdeIcons.Search(shell.mutedText, size = 14.dp) }
                    },
                )
            }
            WelcomeButton("New Document", onNewDocument)
            WelcomeButton("Open", onBrowse)
        }

        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No projects match", color = shell.mutedText, fontSize = IdeaMetrics.SmallFontSize)
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

@Composable
private fun RecentProjectRow(
    project: RecentProject,
    onOpen: (Path) -> Unit,
    onForget: (Path) -> Unit,
) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier.fillMaxWidth().height(IdeaMetrics.WelcomeRecentRowHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) shell.hoverBackground else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onOpen(project.path) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(ShellPalette.badgeColor(project.name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = project.name.take(2).uppercase(),
                fontSize = IdeaMetrics.SmallFontSize,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
            )
        }

        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(project.name, fontSize = IdeaMetrics.UiFontSize, color = shell.text, maxLines = 1)
            Text(
                text = project.displayPath,
                fontSize = IdeaMetrics.TinyFontSize,
                color = shell.mutedText,
                maxLines = 1,
            )
        }

        // Removing an entry appears on hover only, exactly as it does in the IDE's list.
        if (hovered) {
            IdeActionButton(
                onClick = { onForget(project.path) },
                tooltip = "Remove from Recent Projects",
                size = 22.dp,
            ) { tint -> IdeIcons.Close(tint, size = 12.dp) }
        }
    }
}

@Composable
private fun WelcomeButton(label: String, onClick: () -> Unit) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier.height(28.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (hovered) shell.hoverBackground else Color.Transparent)
            .border(1.dp, shell.border, RoundedCornerShape(5.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = IdeaMetrics.SmallFontSize, color = shell.text, maxLines = 1)
    }
}

/** The Customize page: the one setting the welcome window can meaningfully change before a project. */
@Composable
private fun CustomizePane(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    val shell = LocalShellPalette.current

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text("Customize", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = shell.text)
        Text(
            text = "Colour theme",
            fontSize = IdeaMetrics.SmallFontSize,
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
    Box(
        modifier = Modifier.height(30.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) shell.selectionBackground else Color.Transparent)
            .border(1.dp, if (selected) shell.accent else shell.border, RoundedCornerShape(5.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = IdeaMetrics.UiFontSize, color = shell.text, maxLines = 1)
    }
}
