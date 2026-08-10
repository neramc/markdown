namespace Quill.Setup.Core;

/// <summary>
/// The identity the installer, the uninstaller and the Windows registry all agree on.
/// </summary>
/// <remarks>
/// These strings are load-bearing across process boundaries: the uninstaller finds its own
/// installation by <see cref="RegistryKeyName"/>, Explorer finds the file handler by
/// <see cref="ProgId"/>, and an upgrade recognises a previous version by both. Changing one without
/// the other strands an installation that can no longer be removed by its own uninstaller.
/// </remarks>
public static class ProductInfo
{
    /// <summary>Display name, shown in the wizard and in Apps &amp; features.</summary>
    public const string DisplayName = "Quill";

    /// <summary>Publisher, shown in Apps &amp; features and in the UAC prompt.</summary>
    public const string Publisher = "neramc";

    /// <summary>Stable key under the Uninstall hive. Never localise or version this.</summary>
    public const string RegistryKeyName = "Quill";

    /// <summary>ProgId used for the Markdown file association.</summary>
    public const string ProgId = "Quill.Markdown";

    /// <summary>Directory name created under the install root's parent.</summary>
    public const string DirectoryName = "Quill";

    /// <summary>The launcher inside the installed app image.</summary>
    public const string ExecutableRelativePath = @"bin\Quill.exe";

    /// <summary>Name of the uninstaller copied into the install root.</summary>
    public const string UninstallerFileName = "QuillUninstall.exe";

    /// <summary>Name of the manifest written into the install root.</summary>
    public const string ManifestFileName = "install-manifest.json";

    /// <summary>Support and help links surfaced in Apps &amp; features.</summary>
    public const string HelpLink = "https://github.com/neramc/quill";

    /// <summary>Extensions the installer can associate with Quill.</summary>
    public static IReadOnlyList<string> MarkdownExtensions { get; } = [".md", ".markdown"];
}
