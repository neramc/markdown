using System.Collections.Concurrent;

namespace Quill.Setup.Core;

/// <summary>
/// An <see cref="IPlatformIntegration"/> that records what it was asked to do instead of doing it.
/// </summary>
/// <remarks>
/// This backs the test suite and lets the wizard run on a developer's Linux or macOS machine for UI
/// work. It models real state rather than merely logging calls — shortcuts, associations, PATH and
/// the uninstall entry are all kept — so a test can assert that uninstalling leaves nothing behind,
/// which a pure call log cannot express.
/// </remarks>
public sealed class DryRunPlatformIntegration : IPlatformIntegration
{
    private readonly string _root;
    private readonly List<string> _operations = [];
    private readonly ConcurrentDictionary<string, ShortcutDefinition> _shortcuts = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, FileAssociation> _associations = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<InstallScope, List<string>> _paths = new();
    private readonly ConcurrentDictionary<InstallScope, UninstallEntry> _uninstallEntries = new();

    /// <param name="root">
    /// Directory the simulated well-known folders live under, so a test can point everything at a
    /// temporary directory and assert on real files.
    /// </param>
    /// <param name="isElevated">What <see cref="IsElevated"/> reports.</param>
    public DryRunPlatformIntegration(string root, bool isElevated = false)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(root);
        _root = Path.GetFullPath(root);
        IsElevated = isElevated;
    }

    /// <inheritdoc />
    public bool IsElevated { get; }

    /// <summary>Every call made, in order, as human-readable lines.</summary>
    public IReadOnlyList<string> Operations
    {
        get
        {
            lock (_operations)
            {
                return _operations.ToArray();
            }
        }
    }

    /// <summary>Shortcuts that currently exist.</summary>
    public IReadOnlyCollection<ShortcutDefinition> Shortcuts => _shortcuts.Values.ToArray();

    /// <summary>File associations that currently exist, keyed by extension.</summary>
    public IReadOnlyDictionary<string, FileAssociation> Associations =>
        new Dictionary<string, FileAssociation>(_associations, StringComparer.OrdinalIgnoreCase);

    /// <summary>Directories currently on a scope's PATH.</summary>
    public IReadOnlyList<string> PathEntries(InstallScope scope) =>
        _paths.TryGetValue(scope, out var entries) ? entries.ToArray() : [];

    /// <summary>The uninstall entry for a scope, when one was written.</summary>
    public UninstallEntry? UninstallEntryFor(InstallScope scope) =>
        _uninstallEntries.TryGetValue(scope, out var entry) ? entry : null;

    /// <summary>A previously "installed" product, which tests set to exercise upgrade detection.</summary>
    public InstalledProduct? ExistingProduct { get; set; }

    /// <summary>What <see cref="RelaunchElevatedAsync"/> returns.</summary>
    public bool ElevationAccepted { get; set; } = true;

    /// <inheritdoc />
    public string GetDefaultInstallRoot(InstallScope scope) => scope switch
    {
        InstallScope.AllUsers => Path.Combine(_root, "ProgramFiles", ProductInfo.DirectoryName),
        _ => Path.Combine(_root, "LocalAppData", "Programs", ProductInfo.DirectoryName),
    };

    /// <inheritdoc />
    public string GetStartMenuDirectory(InstallScope scope) => scope switch
    {
        InstallScope.AllUsers => Path.Combine(_root, "CommonStartMenu", "Programs"),
        _ => Path.Combine(_root, "StartMenu", "Programs"),
    };

    /// <inheritdoc />
    public string GetDesktopDirectory(InstallScope scope) => scope switch
    {
        InstallScope.AllUsers => Path.Combine(_root, "CommonDesktop"),
        _ => Path.Combine(_root, "Desktop"),
    };

    /// <inheritdoc />
    public void CreateShortcut(ShortcutDefinition shortcut)
    {
        ArgumentNullException.ThrowIfNull(shortcut);
        Record($"CreateShortcut {shortcut.ShortcutPath} -> {shortcut.TargetPath}");
        _shortcuts[shortcut.ShortcutPath] = shortcut;

        // A real .lnk cannot be written portably, but writing *something* means a test can assert
        // the shortcut reached the file system as well as the in-memory state.
        Directory.CreateDirectory(Path.GetDirectoryName(shortcut.ShortcutPath)!);
        File.WriteAllText(shortcut.ShortcutPath, shortcut.TargetPath);
    }

    /// <inheritdoc />
    public void WriteUninstallEntry(InstallScope scope, UninstallEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        Record($"WriteUninstallEntry {scope} {entry.DisplayName} {entry.DisplayVersion}");
        _uninstallEntries[scope] = entry;
    }

    /// <inheritdoc />
    public InstalledProduct? FindInstalledProduct(InstallScope scope) =>
        ExistingProduct is { } product && product.Scope == scope ? product : null;

    /// <inheritdoc />
    public void RegisterFileAssociation(InstallScope scope, FileAssociation association)
    {
        ArgumentNullException.ThrowIfNull(association);
        Record($"RegisterFileAssociation {scope} {association.Extension} -> {association.ProgId}");
        _associations[association.Extension] = association;
    }

    /// <inheritdoc />
    public void AddToPath(InstallScope scope, string directory)
    {
        Record($"AddToPath {scope} {directory}");
        var entries = _paths.GetOrAdd(scope, _ => []);
        lock (entries)
        {
            if (!entries.Contains(directory, StringComparer.OrdinalIgnoreCase))
            {
                entries.Add(directory);
            }
        }
    }

    /// <inheritdoc />
    public void NotifyShellOfChanges() => Record("NotifyShellOfChanges");

    /// <inheritdoc />
    public Task<bool> RelaunchElevatedAsync(
        IReadOnlyList<string> arguments,
        CancellationToken cancellationToken = default)
    {
        Record($"RelaunchElevated [{string.Join(' ', arguments)}]");
        return Task.FromResult(ElevationAccepted);
    }

    private void Record(string operation)
    {
        lock (_operations)
        {
            _operations.Add(operation);
        }
    }
}
