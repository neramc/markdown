using Quill.Setup.Core;

namespace Quill.Setup.Tests;

/// <summary>
/// Tests for the uninstall sequence.
/// </summary>
/// <remarks>
/// The important property is symmetry: after install-then-uninstall the machine is back where it
/// started. The equally important property is restraint — the uninstaller must not delete anything
/// the manifest does not claim, because "clean up the install folder" is how uninstallers eat a
/// user's documents.
/// </remarks>
public sealed class UninstallEngineTests
{
    private static async Task<(TemporaryWorkspace Workspace, DryRunPlatformIntegration Platform, InstallResult Result)>
        InstallAsync(
            InstallScope scope = InstallScope.CurrentUser,
            bool startMenu = true,
            bool desktop = true,
            bool associate = true,
            bool path = true)
    {
        var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var platform = workspace.CreatePlatform(isElevated: scope == InstallScope.AllUsers);
        await using var payload = workspace.OpenPayload();
        var result = await new InstallEngine(platform).InstallAsync(
            payload,
            workspace.Options(scope, startMenu, desktop, associate, path));

        return (workspace, platform, result);
    }

    [Fact]
    public async Task Uninstalling_removes_every_file_the_manifest_records()
    {
        var (workspace, platform, result) = await InstallAsync();
        using var _ = workspace;

        var outcome = await new UninstallEngine(platform).UninstallAsync(result.Manifest);

        Assert.True(outcome.Complete, string.Join("; ", outcome.FilesLeftBehind));
        Assert.Equal(result.Manifest.Files.Count, outcome.FilesRemoved);
        Assert.True(outcome.InstallRootRemoved);
        Assert.False(Directory.Exists(workspace.InstallRoot));
    }

    [Fact]
    public async Task Uninstalling_reverses_every_registration()
    {
        var (workspace, platform, result) = await InstallAsync();
        using var _ = workspace;

        await new UninstallEngine(platform).UninstallAsync(result.Manifest);

        Assert.Null(platform.UninstallEntryFor(InstallScope.CurrentUser));
        Assert.Empty(platform.Associations);
        Assert.Empty(platform.PathEntries(InstallScope.CurrentUser));
        Assert.Empty(platform.Shortcuts);
        Assert.All(result.Manifest.Shortcuts, shortcut => Assert.False(File.Exists(shortcut)));
    }

    [Fact]
    public async Task Registrations_are_removed_before_the_files_they_point_at()
    {
        var (workspace, platform, result) = await InstallAsync();
        using var _ = workspace;

        var operationsBefore = platform.Operations.Count;
        await new UninstallEngine(platform).UninstallAsync(result.Manifest);

        var uninstallOperations = platform.Operations.Skip(operationsBefore).ToList();
        var entryRemoved = uninstallOperations.FindIndex(o => o.StartsWith("DeleteUninstallEntry", StringComparison.Ordinal));
        var shortcutRemoved = uninstallOperations.FindIndex(o => o.StartsWith("DeleteShortcut", StringComparison.Ordinal));

        // The Apps & features entry goes first: if anything later fails, what is left is a partially
        // removed install rather than an entry pointing at an uninstaller that no longer exists.
        Assert.Equal(0, entryRemoved);
        Assert.True(shortcutRemoved > entryRemoved);
    }

    [Fact]
    public async Task An_unrelated_file_in_the_install_folder_survives()
    {
        var (workspace, platform, result) = await InstallAsync();
        using var _ = workspace;

        // The user pointed the installer at a folder they also keep notes in. Deleting the folder
        // wholesale would take the notes with it.
        var keep = Path.Combine(workspace.InstallRoot, "my-notes.md");
        await File.WriteAllTextAsync(keep, "# Do not delete me");

        var outcome = await new UninstallEngine(platform).UninstallAsync(result.Manifest);

        Assert.True(File.Exists(keep));
        Assert.False(outcome.InstallRootRemoved);
        Assert.False(File.Exists(Path.Combine(workspace.InstallRoot, "bin", "Quill.exe")));

        // A root that could not be emptied is handed to the post-exit cleanup, because the running
        // uninstaller lives inside it.
        Assert.Equal(workspace.InstallRoot, platform.ScheduledSelfDelete);
    }

    [Fact]
    public async Task A_missing_file_does_not_stop_the_rest_of_the_removal()
    {
        var (workspace, platform, result) = await InstallAsync();
        using var _ = workspace;

        // Something else already removed part of the installation, which is the normal state after a
        // failed upgrade or a user tidying up by hand.
        File.Delete(Path.Combine(workspace.InstallRoot, "README.txt"));

        var outcome = await new UninstallEngine(platform).UninstallAsync(result.Manifest);

        Assert.True(outcome.Complete);
        Assert.False(Directory.Exists(workspace.InstallRoot));
    }

    [Fact]
    public async Task A_manifest_entry_pointing_outside_the_install_root_is_refused()
    {
        var (workspace, platform, result) = await InstallAsync();
        using var _ = workspace;

        var outsider = Path.Combine(workspace.Root, "outside.txt");
        await File.WriteAllTextAsync(outsider, "not ours to delete");

        var tampered = result.Manifest with
        {
            Files = [.. result.Manifest.Files, "../outside.txt"],
        };

        var outcome = await new UninstallEngine(platform).UninstallAsync(tampered);

        Assert.True(File.Exists(outsider));
        Assert.False(outcome.Complete);
        Assert.Contains(outcome.FilesLeftBehind, entry => entry.Contains("outside", StringComparison.Ordinal));
    }

    [Fact]
    public async Task An_association_another_application_has_taken_over_is_left_alone()
    {
        var (workspace, platform, result) = await InstallAsync();
        using var _ = workspace;

        // Between install and uninstall the user set a different editor as the .md handler.
        platform.RegisterFileAssociation(InstallScope.CurrentUser, new FileAssociation(
            Extension: ".md",
            ProgId: "OtherEditor.Markdown",
            FriendlyTypeName: "Markdown",
            OpenCommand: "\"C:\\Other\\editor.exe\" \"%1\"",
            IconPath: "C:\\Other\\editor.exe"));

        await new UninstallEngine(platform).UninstallAsync(result.Manifest);

        Assert.True(platform.Associations.TryGetValue(".md", out var association));
        Assert.Equal("OtherEditor.Markdown", association.ProgId);
        Assert.False(platform.Associations.ContainsKey(".markdown"));
    }

    [Fact]
    public async Task An_all_users_uninstall_touches_only_the_machine_scope()
    {
        var (workspace, platform, result) = await InstallAsync(InstallScope.AllUsers);
        using var _ = workspace;

        platform.WriteUninstallEntry(InstallScope.CurrentUser, new UninstallEntry(
            "Quill", "0.0.1", "someone else", "C:\\elsewhere", "x", "x", "x", "x", 1));

        await new UninstallEngine(platform).UninstallAsync(result.Manifest);

        Assert.Null(platform.UninstallEntryFor(InstallScope.AllUsers));
        Assert.NotNull(platform.UninstallEntryFor(InstallScope.CurrentUser));
    }

    [Fact]
    public async Task Loading_a_manifest_from_an_installation_round_trips()
    {
        var (workspace, _, result) = await InstallAsync();
        using var _unused = workspace;

        var loaded = await UninstallEngine.LoadManifestAsync(workspace.InstallRoot);

        Assert.Equal(result.Manifest.Version, loaded.Version);
        Assert.Equal(result.Manifest.Files, loaded.Files);
        Assert.Equal(result.Manifest.Shortcuts, loaded.Shortcuts);
        Assert.Equal(result.Manifest.Scope, loaded.Scope);
    }

    [Fact]
    public async Task A_missing_manifest_is_an_explicit_failure_rather_than_a_guess()
    {
        using var workspace = new TemporaryWorkspace();
        Directory.CreateDirectory(workspace.InstallRoot);

        var failure = await Assert.ThrowsAsync<FileNotFoundException>(
            () => UninstallEngine.LoadManifestAsync(workspace.InstallRoot));
        Assert.Contains("will not guess", failure.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_manifest_from_a_newer_installer_is_refused()
    {
        var (workspace, _, result) = await InstallAsync();
        using var _unused = workspace;

        var json = (result.Manifest with { SchemaVersion = InstallManifest.CurrentSchemaVersion + 1 }).ToJson();
        await File.WriteAllTextAsync(
            Path.Combine(workspace.InstallRoot, ProductInfo.ManifestFileName),
            json);

        var failure = await Assert.ThrowsAsync<InvalidDataException>(
            () => UninstallEngine.LoadManifestAsync(workspace.InstallRoot));
        Assert.Contains("schema version", failure.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Install_then_uninstall_leaves_the_machine_as_it_was()
    {
        var (workspace, platform, result) = await InstallAsync();
        using var _ = workspace;

        await new UninstallEngine(platform).UninstallAsync(result.Manifest);

        // Nothing under the simulated Windows folders, nothing in the install tree, no registrations.
        var leftovers = Directory.Exists(workspace.PlatformRoot)
            ? Directory.GetFiles(workspace.PlatformRoot, "*", SearchOption.AllDirectories)
            : [];

        Assert.Empty(leftovers);
        Assert.False(Directory.Exists(workspace.InstallRoot));
        Assert.Empty(platform.Shortcuts);
        Assert.Empty(platform.Associations);
        Assert.Null(platform.UninstallEntryFor(InstallScope.CurrentUser));
    }
}

file static class ListExtensions
{
    internal static int FindIndex(this IReadOnlyList<string> source, Func<string, bool> predicate)
    {
        for (var index = 0; index < source.Count; index++)
        {
            if (predicate(source[index]))
            {
                return index;
            }
        }

        return -1;
    }
}
