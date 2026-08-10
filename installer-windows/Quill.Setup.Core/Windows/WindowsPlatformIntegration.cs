using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Security.Principal;
using Microsoft.Win32;

namespace Quill.Setup.Core.Windows;

/// <summary>
/// The real integration: registry, shell links, PATH, UAC.
/// </summary>
/// <remarks>
/// Everything here is per-scope. <see cref="InstallScope.CurrentUser"/> touches only HKCU and the
/// user's own folders and therefore needs no elevation, which is what lets the default installation
/// path avoid a UAC prompt entirely; <see cref="InstallScope.AllUsers"/> touches HKLM and the common
/// folders and is only reachable after the process has relaunched elevated.
/// </remarks>
[SupportedOSPlatform("windows")]
public sealed partial class WindowsPlatformIntegration : IPlatformIntegration
{
    private const string UninstallKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Uninstall";
    private const string ClassesKeyPath = @"Software\Classes";
    private const string MachineEnvironmentKeyPath =
        @"System\CurrentControlSet\Control\Session Manager\Environment";

    /// <inheritdoc />
    public bool IsElevated
    {
        get
        {
            using var identity = WindowsIdentity.GetCurrent();
            return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
    }

    /// <inheritdoc />
    public string GetDefaultInstallRoot(InstallScope scope) => scope switch
    {
        InstallScope.AllUsers => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            ProductInfo.DirectoryName),

        // %LOCALAPPDATA%\Programs is where per-user applications are expected to live, and unlike
        // Program Files it is writable without elevation.
        _ => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Programs",
            ProductInfo.DirectoryName),
    };

    /// <inheritdoc />
    public string GetStartMenuDirectory(InstallScope scope) => Environment.GetFolderPath(
        scope == InstallScope.AllUsers
            ? Environment.SpecialFolder.CommonPrograms
            : Environment.SpecialFolder.Programs);

    /// <inheritdoc />
    public string GetDesktopDirectory(InstallScope scope) => Environment.GetFolderPath(
        scope == InstallScope.AllUsers
            ? Environment.SpecialFolder.CommonDesktopDirectory
            : Environment.SpecialFolder.DesktopDirectory);

    /// <inheritdoc />
    public void CreateShortcut(ShortcutDefinition shortcut) => ShellLink.Create(shortcut);

    /// <inheritdoc />
    public void DeleteShortcut(string shortcutPath)
    {
        try
        {
            if (File.Exists(shortcutPath))
            {
                File.Delete(shortcutPath);
            }
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            // A shortcut that will not delete must not abort the rest of the uninstall; the caller
            // reports what was left behind.
        }
    }

    /// <inheritdoc />
    public void WriteUninstallEntry(InstallScope scope, UninstallEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);

        using var root = OpenScopeRoot(scope, writable: true);
        using var key = root.CreateSubKey($@"{UninstallKeyPath}\{ProductInfo.RegistryKeyName}", writable: true);

        key.SetValue("DisplayName", entry.DisplayName);
        key.SetValue("DisplayVersion", entry.DisplayVersion);
        key.SetValue("Publisher", entry.Publisher);
        key.SetValue("InstallLocation", entry.InstallLocation);
        key.SetValue("UninstallString", entry.UninstallCommand);
        key.SetValue("QuietUninstallString", entry.QuietUninstallCommand);
        key.SetValue("DisplayIcon", entry.DisplayIcon);
        key.SetValue("HelpLink", entry.HelpLink);
        key.SetValue("URLInfoAbout", entry.HelpLink);
        key.SetValue("EstimatedSize", (int)Math.Min(entry.EstimatedSizeKilobytes, int.MaxValue), RegistryValueKind.DWord);
        key.SetValue("InstallDate", DateTimeOffset.UtcNow.ToString("yyyyMMdd"));
        key.SetValue("NoModify", 1, RegistryValueKind.DWord);
        key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
    }

    /// <inheritdoc />
    public void DeleteUninstallEntry(InstallScope scope)
    {
        using var root = OpenScopeRoot(scope, writable: true);
        using var uninstall = root.OpenSubKey(UninstallKeyPath, writable: true);
        uninstall?.DeleteSubKeyTree(ProductInfo.RegistryKeyName, throwOnMissingSubKey: false);
    }

    /// <inheritdoc />
    public InstalledProduct? FindInstalledProduct(InstallScope scope)
    {
        using var root = OpenScopeRoot(scope, writable: false);
        using var key = root.OpenSubKey($@"{UninstallKeyPath}\{ProductInfo.RegistryKeyName}");
        if (key is null)
        {
            return null;
        }

        var version = key.GetValue("DisplayVersion") as string;
        var location = key.GetValue("InstallLocation") as string;
        return version is null || location is null ? null : new InstalledProduct(version, location, scope);
    }

    /// <inheritdoc />
    public void RegisterFileAssociation(InstallScope scope, FileAssociation association)
    {
        ArgumentNullException.ThrowIfNull(association);

        using var root = OpenScopeRoot(scope, writable: true);
        using var classes = root.CreateSubKey(ClassesKeyPath, writable: true);

        using (var progId = classes.CreateSubKey(association.ProgId, writable: true))
        {
            progId.SetValue(null, association.FriendlyTypeName);
            using (var icon = progId.CreateSubKey("DefaultIcon", writable: true))
            {
                icon.SetValue(null, $"{association.IconPath},0");
            }

            using var command = progId.CreateSubKey(@"shell\open\command", writable: true);
            command.SetValue(null, association.OpenCommand);
        }

        using var extension = classes.CreateSubKey(association.Extension, writable: true);

        // The previous handler is preserved so uninstall can hand the extension back rather than
        // leaving the user with a file type nothing opens.
        if (extension.GetValue(null) is string previous &&
            !string.Equals(previous, association.ProgId, StringComparison.OrdinalIgnoreCase) &&
            extension.GetValue("Quill.Backup") is null)
        {
            extension.SetValue("Quill.Backup", previous);
        }

        extension.SetValue(null, association.ProgId);

        using var openWith = extension.CreateSubKey(@"OpenWithProgids", writable: true);
        openWith.SetValue(association.ProgId, Array.Empty<byte>(), RegistryValueKind.None);
    }

    /// <inheritdoc />
    public void UnregisterFileAssociation(InstallScope scope, string extension, string progId)
    {
        using var root = OpenScopeRoot(scope, writable: true);
        using var classes = root.OpenSubKey(ClassesKeyPath, writable: true);
        if (classes is null)
        {
            return;
        }

        classes.DeleteSubKeyTree(progId, throwOnMissingSubKey: false);

        using var extensionKey = classes.OpenSubKey(extension, writable: true);
        if (extensionKey is null)
        {
            return;
        }

        using (var openWith = extensionKey.OpenSubKey("OpenWithProgids", writable: true))
        {
            openWith?.DeleteValue(progId, throwOnMissingValue: false);
        }

        // Only give the extension back if it is still ours: another editor may have claimed it since
        // installation, and taking it away from them on our uninstall would be the greater sin.
        if (extensionKey.GetValue(null) is string current &&
            string.Equals(current, progId, StringComparison.OrdinalIgnoreCase))
        {
            if (extensionKey.GetValue("Quill.Backup") is string previous)
            {
                extensionKey.SetValue(null, previous);
                extensionKey.DeleteValue("Quill.Backup", throwOnMissingValue: false);
            }
            else
            {
                extensionKey.DeleteValue(string.Empty, throwOnMissingValue: false);
            }
        }
    }

    /// <inheritdoc />
    public void AddToPath(InstallScope scope, string directory)
    {
        var target = scope == InstallScope.AllUsers
            ? EnvironmentVariableTarget.Machine
            : EnvironmentVariableTarget.User;

        var current = ReadRawPath(scope) ?? string.Empty;
        var entries = current.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (entries.Contains(directory, StringComparer.OrdinalIgnoreCase))
        {
            return;
        }

        var updated = entries.Length == 0 ? directory : $"{string.Join(';', entries)};{directory}";
        Environment.SetEnvironmentVariable("Path", updated, target);
    }

    /// <inheritdoc />
    public void RemoveFromPath(InstallScope scope, string directory)
    {
        var target = scope == InstallScope.AllUsers
            ? EnvironmentVariableTarget.Machine
            : EnvironmentVariableTarget.User;

        var current = ReadRawPath(scope);
        if (string.IsNullOrEmpty(current))
        {
            return;
        }

        var entries = current
            .Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(entry => !string.Equals(entry, directory, StringComparison.OrdinalIgnoreCase))
            .ToArray();

        Environment.SetEnvironmentVariable("Path", string.Join(';', entries), target);
    }

    /// <inheritdoc />
    public void NotifyShellOfChanges()
    {
        // Tells Explorer that file associations changed, and every top-level window that the
        // environment did. Without the first, the new icon does not appear until logoff; without the
        // second, no already-running shell picks up the PATH entry.
        NativeMethods.SHChangeNotify(NativeMethods.ShcneAssocchanged, NativeMethods.ShcnfIdlist, 0, 0);

        _ = NativeMethods.SendMessageTimeout(
            NativeMethods.HwndBroadcast,
            NativeMethods.WmSettingchange,
            0,
            "Environment",
            NativeMethods.SmtoAbortifhung,
            5000,
            out _);
    }

    /// <inheritdoc />
    public Task<bool> RelaunchElevatedAsync(
        IReadOnlyList<string> arguments,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(arguments);
        cancellationToken.ThrowIfCancellationRequested();

        var executable = Environment.ProcessPath;
        if (string.IsNullOrEmpty(executable))
        {
            return Task.FromResult(false);
        }

        var startInfo = new ProcessStartInfo(executable)
        {
            // "runas" is what raises the UAC prompt, and UseShellExecute is what makes the verb
            // work at all. The manifest deliberately requests asInvoker so this only happens when
            // the user actually chose an all-users installation.
            Verb = "runas",
            UseShellExecute = true,
        };

        foreach (var argument in arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        try
        {
            using var process = Process.Start(startInfo);
            return Task.FromResult(process is not null);
        }
        catch (System.ComponentModel.Win32Exception)
        {
            // The user dismissed the UAC prompt. That is a decision, not a failure.
            return Task.FromResult(false);
        }
    }

    /// <inheritdoc />
    public void ScheduleSelfDelete(string directory)
    {
        // cmd waits for this process to release its image, then removes the folder and finally the
        // batch file itself. A detached, window-less cmd is the only mechanism that survives the
        // process it is cleaning up after.
        var script = Path.Combine(Path.GetTempPath(), $"quill-cleanup-{Guid.NewGuid():N}.cmd");
        var contents =
            $"""
            @echo off
            ping 127.0.0.1 -n 4 >nul
            rmdir /s /q "{directory}"
            del "%~f0"
            """;

        File.WriteAllText(script, contents);

        using var _ = Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{script}\"")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            WindowStyle = ProcessWindowStyle.Hidden,
        });
    }

    private static RegistryKey OpenScopeRoot(InstallScope scope, bool writable)
    {
        _ = writable;
        return scope == InstallScope.AllUsers
            ? RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64)
            : RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Default);
    }

    /// <summary>
    /// Reads PATH without expanding it.
    /// </summary>
    /// <remarks>
    /// <c>Environment.GetEnvironmentVariable</c> expands embedded <c>%VAR%</c> references, and
    /// writing the expanded result back is how installers permanently flatten a user's PATH. Going
    /// through the registry with <see cref="RegistryValueOptions.DoNotExpandEnvironmentNames"/>
    /// keeps the value exactly as the user wrote it.
    /// </remarks>
    private static string? ReadRawPath(InstallScope scope)
    {
        using var root = OpenScopeRoot(scope, writable: false);
        var keyPath = scope == InstallScope.AllUsers ? MachineEnvironmentKeyPath : "Environment";
        using var key = root.OpenSubKey(keyPath);
        return key?.GetValue("Path", string.Empty, RegistryValueOptions.DoNotExpandEnvironmentNames) as string;
    }

    private static partial class NativeMethods
    {
        internal const int ShcneAssocchanged = 0x08000000;
        internal const uint ShcnfIdlist = 0x0000;
        internal const nint HwndBroadcast = 0xFFFF;
        internal const uint WmSettingchange = 0x001A;
        internal const uint SmtoAbortifhung = 0x0002;

        [LibraryImport("shell32.dll")]
        internal static partial void SHChangeNotify(int eventId, uint flags, nint item1, nint item2);

        [LibraryImport("user32.dll", EntryPoint = "SendMessageTimeoutW", StringMarshalling = StringMarshalling.Utf16)]
        internal static partial nint SendMessageTimeout(
            nint window,
            uint message,
            nint wParam,
            string lParam,
            uint flags,
            uint timeoutMilliseconds,
            out nint result);
    }
}
