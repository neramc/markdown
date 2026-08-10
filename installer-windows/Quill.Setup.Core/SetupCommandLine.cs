namespace Quill.Setup.Core;

/// <summary>Options parsed from the process arguments.</summary>
/// <param name="Silent">Run with no window at all — <c>/S</c>, the convention Windows uninstallers use.</param>
/// <param name="AllUsers">Machine-wide installation.</param>
/// <param name="TargetDirectory">Explicit install root, or null to use the scope default.</param>
/// <param name="CreateStartMenuShortcut">Start menu entry requested.</param>
/// <param name="CreateDesktopShortcut">Desktop icon requested.</param>
/// <param name="AssociateMarkdown">File association requested.</param>
/// <param name="AddToPath">PATH entry requested.</param>
/// <param name="HasComponentFlags">
/// Whether any component switch was given. Distinguishes "the user asked for nothing" from "nothing
/// was specified, so use the defaults", which matters because the elevated relaunch passes exactly
/// the switches the user ticked and no others.
/// </param>
public sealed record SetupCommandLine(
    bool Silent,
    bool AllUsers,
    string? TargetDirectory,
    bool CreateStartMenuShortcut,
    bool CreateDesktopShortcut,
    bool AssociateMarkdown,
    bool AddToPath,
    bool HasComponentFlags)
{
    /// <summary>Parses the arguments the wizard and the elevated relaunch understand.</summary>
    public static SetupCommandLine Parse(IReadOnlyList<string> arguments)
    {
        ArgumentNullException.ThrowIfNull(arguments);

        var silent = false;
        var allUsers = false;
        string? target = null;
        var startMenu = false;
        var desktop = false;
        var associate = false;
        var addToPath = false;
        var hasComponentFlags = false;

        for (var index = 0; index < arguments.Count; index++)
        {
            var argument = arguments[index];

            switch (argument.ToLowerInvariant())
            {
                case "/s":
                case "--silent":
                    silent = true;
                    break;

                case "--all-users":
                    allUsers = true;
                    break;

                case "--current-user":
                    allUsers = false;
                    break;

                case "--target":
                    if (index + 1 < arguments.Count)
                    {
                        target = arguments[++index];
                    }

                    break;

                case "--start-menu":
                    startMenu = true;
                    hasComponentFlags = true;
                    break;

                case "--desktop":
                    desktop = true;
                    hasComponentFlags = true;
                    break;

                case "--associate":
                    associate = true;
                    hasComponentFlags = true;
                    break;

                case "--add-to-path":
                    addToPath = true;
                    hasComponentFlags = true;
                    break;

                default:
                    if (argument.StartsWith("--target=", StringComparison.OrdinalIgnoreCase))
                    {
                        target = argument["--target=".Length..];
                    }

                    break;
            }
        }

        return new SetupCommandLine(
            silent, allUsers, target, startMenu, desktop, associate, addToPath, hasComponentFlags);
    }

    /// <summary>Turns the parsed arguments into install options, filling gaps from the platform.</summary>
    public InstallOptions ToInstallOptions(IPlatformIntegration platform)
    {
        ArgumentNullException.ThrowIfNull(platform);

        var scope = AllUsers ? InstallScope.AllUsers : InstallScope.CurrentUser;
        return new InstallOptions(
            Scope: scope,
            TargetDirectory: TargetDirectory ?? platform.GetDefaultInstallRoot(scope),
            // With no switches at all the defaults apply, which is what makes a bare `/S` do the
            // sensible thing rather than installing a shortcut-less, unassociated copy.
            CreateStartMenuShortcut: HasComponentFlags ? CreateStartMenuShortcut : true,
            CreateDesktopShortcut: HasComponentFlags ? CreateDesktopShortcut : false,
            AssociateMarkdown: HasComponentFlags ? AssociateMarkdown : true,
            AddToPath: HasComponentFlags && AddToPath);
    }
}
