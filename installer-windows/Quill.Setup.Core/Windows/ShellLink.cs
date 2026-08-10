using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Runtime.Versioning;

namespace Quill.Setup.Core.Windows;

/// <summary>
/// Minimal <c>IShellLink</c> interop for writing .lnk files.
/// </summary>
/// <remarks>
/// Windows has no managed shortcut API, and the two usual workarounds are both worse than this:
/// driving WScript.Shell through late-bound COM breaks under single-file publishing, and writing the
/// .lnk binary format by hand means reimplementing a shell format for no benefit. Declaring the two
/// interfaces the shell already exposes is the smallest correct option.
/// </remarks>
[SupportedOSPlatform("windows")]
internal static class ShellLink
{
    /// <summary>Creates or replaces a shortcut file.</summary>
    internal static void Create(ShortcutDefinition definition)
    {
        ArgumentNullException.ThrowIfNull(definition);

        Directory.CreateDirectory(Path.GetDirectoryName(definition.ShortcutPath)!);

        // The cast goes through object deliberately. A [ComImport] class declares no interfaces the
        // compiler can see, so a direct cast is a compile error; going through object turns it into
        // the runtime QueryInterface that actually happens here.
        object instance = new ShellLinkCoClass();
        var link = (IShellLinkW)instance;
        try
        {
            link.SetPath(definition.TargetPath);
            link.SetWorkingDirectory(definition.WorkingDirectory);
            link.SetDescription(Truncate(definition.Description, 259));

            if (!string.IsNullOrEmpty(definition.IconPath))
            {
                link.SetIconLocation(definition.IconPath, 0);
            }

            ((IPersistFile)link).Save(definition.ShortcutPath, fRemember: true);
        }
        finally
        {
            // The RCW holds the only reference to the COM object; without this the shortcut is
            // written but the object lingers until a GC that may never come in a short-lived
            // installer process.
            Marshal.FinalReleaseComObject(link);
        }
    }

    /// <summary>IShellLinkW's description field is bounded; an over-long value fails the call.</summary>
    private static string Truncate(string value, int maximumLength) =>
        value.Length <= maximumLength ? value : value[..maximumLength];

    [ComImport]
    [Guid("00021401-0000-0000-C000-000000000046")]
    [ClassInterface(ClassInterfaceType.None)]
    private sealed class ShellLinkCoClass;

    [ComImport]
    [Guid("000214F9-0000-0000-C000-000000000046")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellLinkW
    {
        void GetPath(
            [MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder file,
            int maxPath,
            nint findData,
            int flags);

        void GetIDList(out nint idList);

        void SetIDList(nint idList);

        void GetDescription([MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder name, int maxName);

        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string name);

        void GetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder directory, int maxPath);

        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string directory);

        void GetArguments([MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder arguments, int maxPath);

        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string arguments);

        void GetHotkey(out short hotkey);

        void SetHotkey(short hotkey);

        void GetShowCmd(out int showCommand);

        void SetShowCmd(int showCommand);

        void GetIconLocation(
            [MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder iconPath,
            int iconPathLength,
            out int iconIndex);

        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string iconPath, int iconIndex);

        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string relativePath, int reserved);

        void Resolve(nint window, int flags);

        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string file);
    }
}
