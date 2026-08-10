namespace Quill.Setup.Core;

/// <summary>A shortcut to create.</summary>
/// <param name="ShortcutPath">Absolute path of the .lnk file.</param>
/// <param name="TargetPath">Absolute path of the executable it points at.</param>
/// <param name="WorkingDirectory">Working directory the target is launched in.</param>
/// <param name="Description">Tooltip text.</param>
/// <param name="IconPath">Absolute path of the icon source, usually the target itself.</param>
public sealed record ShortcutDefinition(
    string ShortcutPath,
    string TargetPath,
    string WorkingDirectory,
    string Description,
    string? IconPath = null);

/// <summary>The values that appear in Apps &amp; features.</summary>
public sealed record UninstallEntry(
    string DisplayName,
    string DisplayVersion,
    string Publisher,
    string InstallLocation,
    string UninstallCommand,
    string QuietUninstallCommand,
    string DisplayIcon,
    string HelpLink,
    long EstimatedSizeKilobytes);

/// <summary>A file-type registration.</summary>
/// <param name="Extension">Extension including the dot, lowercase.</param>
/// <param name="ProgId">The ProgId the extension points at.</param>
/// <param name="FriendlyTypeName">What Explorer shows in the Type column.</param>
/// <param name="OpenCommand">Command line with a "%1" placeholder for the file.</param>
/// <param name="IconPath">Icon source for files of this type.</param>
public sealed record FileAssociation(
    string Extension,
    string ProgId,
    string FriendlyTypeName,
    string OpenCommand,
    string IconPath);

/// <summary>An installation already present on the machine.</summary>
public sealed record InstalledProduct(string DisplayVersion, string InstallLocation, InstallScope Scope);

/// <summary>
/// Everything the installer needs from the operating system that is not a file copy.
/// </summary>
/// <remarks>
/// The registry, the shell namespace, PATH and UAC are the parts of an installer that cannot be
/// exercised anywhere but Windows. Behind this interface they become substitutable, so the install
/// and uninstall *sequences* — the parts where an ordering mistake leaves a machine with dead
/// shortcuts or an unremovable Apps &amp; features entry — are testable on any platform.
/// </remarks>
public interface IPlatformIntegration
{
    /// <summary>Whether the current process can write to machine-wide locations.</summary>
    bool IsElevated { get; }

    /// <summary>The conventional install root for a scope, e.g. %LOCALAPPDATA%\Programs\Quill.</summary>
    string GetDefaultInstallRoot(InstallScope scope);

    /// <summary>Directory holding Start menu program shortcuts for a scope.</summary>
    string GetStartMenuDirectory(InstallScope scope);

    /// <summary>The desktop directory for a scope.</summary>
    string GetDesktopDirectory(InstallScope scope);

    /// <summary>Creates or replaces a shortcut.</summary>
    void CreateShortcut(ShortcutDefinition shortcut);

    /// <summary>Removes a shortcut, tolerating one that is already gone.</summary>
    void DeleteShortcut(string shortcutPath);

    /// <summary>Writes the Apps &amp; features entry.</summary>
    void WriteUninstallEntry(InstallScope scope, UninstallEntry entry);

    /// <summary>Removes the Apps &amp; features entry, tolerating one that is already gone.</summary>
    void DeleteUninstallEntry(InstallScope scope);

    /// <summary>Finds an existing installation, for upgrade and repair detection.</summary>
    InstalledProduct? FindInstalledProduct(InstallScope scope);

    /// <summary>Registers a file type.</summary>
    void RegisterFileAssociation(InstallScope scope, FileAssociation association);

    /// <summary>Unregisters a file type, leaving another application's registration alone.</summary>
    void UnregisterFileAssociation(InstallScope scope, string extension, string progId);

    /// <summary>Appends a directory to the scope's PATH if it is not already there.</summary>
    void AddToPath(InstallScope scope, string directory);

    /// <summary>Removes a directory from the scope's PATH.</summary>
    void RemoveFromPath(InstallScope scope, string directory);

    /// <summary>Tells the shell that environment or association state changed.</summary>
    void NotifyShellOfChanges();

    /// <summary>
    /// Relaunches the current executable elevated.
    /// </summary>
    /// <returns><see langword="true"/> when the elevated process started; false when the user declined.</returns>
    Task<bool> RelaunchElevatedAsync(IReadOnlyList<string> arguments, CancellationToken cancellationToken = default);

    /// <summary>
    /// Arranges for <paramref name="directory"/> to be removed after this process exits.
    /// </summary>
    /// <remarks>
    /// The uninstaller lives inside the directory it is deleting and cannot remove its own running
    /// image, so the last step has to outlive the process.
    /// </remarks>
    void ScheduleSelfDelete(string directory);
}
