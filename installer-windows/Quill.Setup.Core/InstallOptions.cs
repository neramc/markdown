namespace Quill.Setup.Core;

/// <summary>Who the installation is for, which decides both the target and whether UAC is needed.</summary>
public enum InstallScope
{
    /// <summary>
    /// Installs under the user's own profile. Needs no elevation, which is why it is the default:
    /// an editor is a per-user tool, and a wizard that opens with a UAC prompt is a wizard most
    /// people cancel.
    /// </summary>
    CurrentUser,

    /// <summary>Installs under Program Files for every account. Requires elevation.</summary>
    AllUsers,
}

/// <summary>Everything the user chose in the wizard, and the only input <see cref="InstallEngine"/> takes.</summary>
/// <param name="Scope">Per-user or all-users.</param>
/// <param name="TargetDirectory">Absolute install root. Never a relative or empty path.</param>
/// <param name="CreateStartMenuShortcut">Whether to add a Start menu entry.</param>
/// <param name="CreateDesktopShortcut">Whether to add a desktop icon.</param>
/// <param name="AssociateMarkdown">Whether to register Quill as a handler for .md and .markdown.</param>
/// <param name="AddToPath">Whether to put the launcher directory on PATH.</param>
public sealed record InstallOptions(
    InstallScope Scope,
    string TargetDirectory,
    bool CreateStartMenuShortcut = true,
    bool CreateDesktopShortcut = false,
    bool AssociateMarkdown = true,
    bool AddToPath = false)
{
    /// <summary>
    /// Rejects a target that cannot be installed into.
    /// </summary>
    /// <remarks>
    /// Validation happens before a single byte is written. An installer that has already unpacked
    /// half a payload before discovering the path was unusable is an installer that leaves debris.
    /// </remarks>
    /// <returns>An error message, or <see langword="null"/> when the options are usable.</returns>
    public string? Validate()
    {
        if (string.IsNullOrWhiteSpace(TargetDirectory))
        {
            return "Choose an installation folder.";
        }

        if (!Path.IsPathRooted(TargetDirectory))
        {
            return "The installation folder must be an absolute path.";
        }

        if (TargetDirectory.AsSpan().IndexOfAny(Path.GetInvalidPathChars()) >= 0)
        {
            return "The installation folder contains characters that are not valid in a path.";
        }

        // A file where the directory should be, or an existing directory with unrelated content,
        // both end badly during uninstall — the manifest would claim files the installer did not
        // put there.
        if (File.Exists(TargetDirectory))
        {
            return "A file already exists at that location.";
        }

        return null;
    }
}
