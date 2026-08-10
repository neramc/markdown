using Quill.Setup.Core;

namespace Quill.Setup.Tests;

/// <summary>
/// Tests for the install sequence.
/// </summary>
/// <remarks>
/// These are the checks that would otherwise need a Windows VM and a snapshot per run. The
/// registrations are simulated, but the ordering, the manifest contents and the file layout are the
/// real thing, and those are where installer bugs actually live.
/// </remarks>
public sealed class InstallEngineTests
{
    [Fact]
    public async Task A_default_install_lays_the_image_out_and_records_it()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        var index = await workspace.BuildPayloadAsync("1.2.3");

        var platform = workspace.CreatePlatform();
        var engine = new InstallEngine(platform);

        await using var payload = workspace.OpenPayload();
        var result = await engine.InstallAsync(payload, workspace.Options());

        Assert.False(result.WasUpgrade);
        Assert.True(File.Exists(Path.Combine(workspace.InstallRoot, "bin", "Quill.exe")));
        Assert.True(File.Exists(result.ManifestPath));

        var manifest = result.Manifest;
        Assert.Equal("1.2.3", manifest.Version);
        Assert.Equal(index.Entries.Count, manifest.Files.Count);
        Assert.Equal(InstallScope.CurrentUser, manifest.Scope);
        Assert.Equal(workspace.InstallRoot, manifest.InstallRoot);
        Assert.True(manifest.UninstallEntryWritten);

        // The manifest on disk must round-trip, because the uninstaller only ever sees that copy.
        var reloaded = InstallManifest.FromJson(await File.ReadAllTextAsync(result.ManifestPath));
        Assert.Equal(manifest.Files, reloaded.Files);
        Assert.Equal(manifest.InstallRoot, reloaded.InstallRoot);
    }

    [Fact]
    public async Task The_uninstall_entry_points_at_the_installed_uninstaller()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync("4.5.6");

        var platform = workspace.CreatePlatform();
        await using var payload = workspace.OpenPayload();
        await new InstallEngine(platform).InstallAsync(payload, workspace.Options());

        var entry = platform.UninstallEntryFor(InstallScope.CurrentUser);
        Assert.NotNull(entry);
        Assert.Equal("Quill", entry.DisplayName);
        Assert.Equal("4.5.6", entry.DisplayVersion);
        Assert.Equal(workspace.InstallRoot, entry.InstallLocation);
        Assert.Contains(ProductInfo.UninstallerFileName, entry.UninstallCommand, StringComparison.Ordinal);

        // Apps & features runs QuietUninstallString for a silent removal; without /S the uninstaller
        // would pop a window during an unattended uninstall.
        Assert.EndsWith("/S\"", entry.QuietUninstallCommand + "\"", StringComparison.Ordinal);
        Assert.True(entry.EstimatedSizeKilobytes > 0);
    }

    [Fact]
    public async Task Shortcuts_are_created_only_where_asked()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var platform = workspace.CreatePlatform();
        await using var payload = workspace.OpenPayload();
        var result = await new InstallEngine(platform).InstallAsync(
            payload,
            workspace.Options(startMenu: true, desktop: true));

        Assert.Equal(2, result.Manifest.Shortcuts.Count);
        Assert.All(platform.Shortcuts, shortcut =>
            Assert.Equal(Path.Combine(workspace.InstallRoot, "bin", "Quill.exe"), shortcut.TargetPath));
        Assert.All(result.Manifest.Shortcuts, path => Assert.True(File.Exists(path)));
    }

    [Fact]
    public async Task Declining_every_optional_component_registers_nothing_extra()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var platform = workspace.CreatePlatform();
        await using var payload = workspace.OpenPayload();
        var result = await new InstallEngine(platform).InstallAsync(
            payload,
            workspace.Options(startMenu: false, desktop: false, associate: false, path: false));

        Assert.Empty(result.Manifest.Shortcuts);
        Assert.Empty(result.Manifest.FileAssociations);
        Assert.Null(result.Manifest.PathEntry);
        Assert.Empty(platform.Associations);
        Assert.Empty(platform.PathEntries(InstallScope.CurrentUser));

        // The uninstall entry is not optional: an installation Windows cannot remove is worse than
        // one with no shortcuts.
        Assert.NotNull(platform.UninstallEntryFor(InstallScope.CurrentUser));
    }

    [Fact]
    public async Task Markdown_extensions_are_associated_with_the_launcher()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var platform = workspace.CreatePlatform();
        await using var payload = workspace.OpenPayload();
        await new InstallEngine(platform).InstallAsync(payload, workspace.Options(associate: true));

        Assert.Equal(ProductInfo.MarkdownExtensions.Count, platform.Associations.Count);
        foreach (var extension in ProductInfo.MarkdownExtensions)
        {
            var association = platform.Associations[extension];
            Assert.Equal(ProductInfo.ProgId, association.ProgId);
            // The "%1" placeholder is what makes double-clicking a file open it rather than opening
            // an empty editor.
            Assert.Contains("\"%1\"", association.OpenCommand, StringComparison.Ordinal);
        }
    }

    [Fact]
    public async Task The_path_entry_is_the_launcher_directory_not_the_install_root()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var platform = workspace.CreatePlatform();
        await using var payload = workspace.OpenPayload();
        var result = await new InstallEngine(platform).InstallAsync(payload, workspace.Options(path: true));

        var expected = Path.Combine(workspace.InstallRoot, "bin");
        Assert.Equal(expected, result.Manifest.PathEntry);
        Assert.Equal([expected], platform.PathEntries(InstallScope.CurrentUser));
    }

    [Fact]
    public async Task An_all_users_install_without_elevation_is_refused_before_writing_anything()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var platform = workspace.CreatePlatform(isElevated: false);
        await using var payload = workspace.OpenPayload();

        var failure = await Assert.ThrowsAsync<InvalidOperationException>(
            () => new InstallEngine(platform).InstallAsync(payload, workspace.Options(InstallScope.AllUsers)));

        Assert.Contains("administrator", failure.Message, StringComparison.OrdinalIgnoreCase);
        Assert.False(Directory.Exists(workspace.InstallRoot));
        Assert.Empty(platform.Operations);
    }

    [Fact]
    public async Task An_all_users_install_succeeds_when_elevated()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var platform = workspace.CreatePlatform(isElevated: true);
        await using var payload = workspace.OpenPayload();
        var result = await new InstallEngine(platform).InstallAsync(
            payload,
            workspace.Options(InstallScope.AllUsers));

        Assert.Equal(InstallScope.AllUsers, result.Manifest.Scope);
        Assert.NotNull(platform.UninstallEntryFor(InstallScope.AllUsers));
        Assert.Null(platform.UninstallEntryFor(InstallScope.CurrentUser));
    }

    [Fact]
    public async Task An_existing_installation_is_reported_as_an_upgrade()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync("2.0.0");

        var platform = workspace.CreatePlatform();
        platform.ExistingProduct = new InstalledProduct("1.0.0", workspace.InstallRoot, InstallScope.CurrentUser);

        await using var payload = workspace.OpenPayload();
        var result = await new InstallEngine(platform).InstallAsync(payload, workspace.Options());

        Assert.True(result.WasUpgrade);
        Assert.Equal("2.0.0", platform.UninstallEntryFor(InstallScope.CurrentUser)!.DisplayVersion);
    }

    [Fact]
    public async Task Registration_happens_only_after_the_files_are_in_place()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var platform = workspace.CreatePlatform();
        await using var payload = workspace.OpenPayload();
        await new InstallEngine(platform).InstallAsync(payload, workspace.Options(desktop: true, path: true));

        // Every shortcut the integration was asked to create already pointed at a file that existed,
        // which is the ordering guarantee that stops a half-finished install leaving dead entries in
        // the Start menu.
        Assert.All(platform.Shortcuts, shortcut => Assert.True(File.Exists(shortcut.TargetPath)));

        var operations = platform.Operations;
        var firstRegistration = operations.FindIndex(operation => operation.StartsWith("CreateShortcut", StringComparison.Ordinal));
        var uninstallEntry = operations.FindIndex(operation => operation.StartsWith("WriteUninstallEntry", StringComparison.Ordinal));
        Assert.True(firstRegistration >= 0 && uninstallEntry > firstRegistration,
            "the uninstall entry must be written last, once everything it describes exists");
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("relative/path")]
    public async Task An_unusable_target_directory_is_rejected(string target)
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var platform = workspace.CreatePlatform();
        await using var payload = workspace.OpenPayload();

        await Assert.ThrowsAsync<InvalidOperationException>(() => new InstallEngine(platform)
            .InstallAsync(payload, new InstallOptions(InstallScope.CurrentUser, target)));
    }

    [Fact]
    public async Task A_file_where_the_install_folder_should_be_is_rejected()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var blocker = Path.Combine(workspace.Root, "blocked");
        await File.WriteAllTextAsync(blocker, "not a directory");

        var options = new InstallOptions(InstallScope.CurrentUser, blocker);
        Assert.NotNull(options.Validate());
    }
}

file static class ListExtensions
{
    /// <summary>Index of the first match, or -1 — the List&lt;T&gt; helper, for a read-only list.</summary>
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
