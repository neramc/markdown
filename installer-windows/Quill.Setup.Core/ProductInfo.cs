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

    /// <summary>
    /// The launcher inside the installed app image, relative to the install root.
    /// </summary>
    /// <remarks>
    /// At the root, with <c>app\</c> and <c>runtime\</c> beside it. That is jpackage's Windows
    /// layout, and it is not the Linux one — there the launcher is <c>bin/Quill</c> and the rest is
    /// under <c>lib/</c>. The payload is always built from a Windows app image, so this is the only
    /// layout that reaches an installation.
    ///
    /// Everything Windows knows about Quill is built from this path: the Start menu and desktop
    /// shortcuts, the <c>.md</c> handler, the icon in Apps &amp; features and the uninstall command.
    /// Get it wrong and setup reports success while leaving shortcuts that launch nothing and an
    /// entry that cannot be uninstalled — so <see cref="InstallEngine"/> checks the payload actually
    /// contains it rather than trusting this constant.
    /// </remarks>
    public const string ExecutableRelativePath = "Quill.exe";

    /// <summary>
    /// The switch that makes the installed application remove itself.
    /// </summary>
    /// <remarks>
    /// There is no uninstaller executable. There used to be — a self-contained .NET application
    /// whose only job was to delete files, which shipped in every release and then sat in the
    /// install folder forever, larger than the editor it removed. Quill removes itself instead, so
    /// the remover is always present, always the same version as what it is removing, and costs
    /// nothing to ship. <c>ProductInfo</c> only has to agree with the application on this one word.
    /// </remarks>
    public const string UninstallSwitch = "--uninstall";

    /// <summary>Name of the manifest written into the install root.</summary>
    public const string ManifestFileName = "install-manifest.json";

    /// <summary>
    /// The icon Explorer draws next to a Markdown file Quill has claimed, relative to the install
    /// root.
    /// </summary>
    /// <remarks>
    /// A separate icon from the application's, because the two are shown side by side constantly
    /// and "a Quill document" and "Quill" being the same picture makes a folder listing unreadable.
    /// Staged into the payload by <c>tools/build-installer.sh</c>; when it is absent the
    /// association falls back to the launcher's own icon rather than registering a path to nothing.
    /// </remarks>
    public const string DocumentIconFileName = "document.ico";

    /// <summary>Support and help links surfaced in Apps &amp; features.</summary>
    public const string HelpLink = "https://github.com/neramc/quill";

    /// <summary>Extensions the installer can associate with Quill.</summary>
    public static IReadOnlyList<string> MarkdownExtensions { get; } = [".md", ".markdown"];
}
