using System.Reflection;
using Avalonia;
using Quill.Setup.Core;

namespace Quill.Installer;

/// <summary>Entry point for QuillSetup.exe.</summary>
internal static class Program
{
    /// <summary>
    /// Runs the wizard, or performs a silent installation when <c>/S</c> is given.
    /// </summary>
    /// <remarks>
    /// STAThread is not optional here: the shortcut writer talks to the shell through COM, and
    /// apartment-threaded COM objects cannot be created from an MTA thread.
    /// </remarks>
    [STAThread]
    public static int Main(string[] args)
    {
        var command = SetupCommandLine.Parse(args);

        if (command.Silent)
        {
            // A silent install has no window to report into, so its result is the exit code and its
            // detail goes to standard error. That is what an unattended deployment can act on.
            return RunSilent(args, command).GetAwaiter().GetResult();
        }

        App.Arguments = args;
        App.DryRunRoot = ReadDryRunRoot(args);

        return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
    }

    /// <summary>Avalonia's builder; also used by the design-time tooling.</summary>
    public static AppBuilder BuildAvaloniaApp() => AppBuilder
        .Configure<App>()
        .UsePlatformDetect()
        .WithInterFont()
        .LogToTrace();

    private static async Task<int> RunSilent(string[] args, SetupCommandLine command)
    {
        var platform = PlatformIntegrationFactory.Create(ReadDryRunRoot(args));

        try
        {
            var (payload, origin) = PayloadSource.Open(Assembly.GetExecutingAssembly(), args);
            if (payload is null)
            {
                await Console.Error.WriteLineAsync(
                    "This installer carries no application payload. Pass --payload <archive> or use a " +
                    "build produced by tools/build-installer.sh.");
                return 2;
            }

            await using (payload)
            {
                var options = command.ToInstallOptions(platform);
                var result = await new InstallEngine(platform).InstallAsync(payload, options);

                Console.WriteLine(
                    $"Installed Quill {result.Manifest.Version} into {result.Manifest.InstallRoot} " +
                    $"({result.Manifest.Files.Count} files, payload from {origin}).");
                return 0;
            }
        }
        catch (Exception exception) when (exception is InvalidOperationException or InvalidDataException
                                              or IOException or UnauthorizedAccessException)
        {
            await Console.Error.WriteLineAsync(exception.Message);
            return 1;
        }
    }

    /// <summary>
    /// Reads the directory the dry-run integration should simulate Windows folders under.
    /// </summary>
    /// <remarks>
    /// Only meaningful off Windows, where it lets the wizard be run and screenshotted against a
    /// temporary directory instead of a real machine.
    /// </remarks>
    private static string? ReadDryRunRoot(IReadOnlyList<string> args)
    {
        const string Switch = "--dry-run-root";

        for (var index = 0; index < args.Count; index++)
        {
            if (string.Equals(args[index], Switch, StringComparison.OrdinalIgnoreCase) && index + 1 < args.Count)
            {
                return args[index + 1];
            }

            if (args[index].StartsWith(Switch + "=", StringComparison.OrdinalIgnoreCase))
            {
                return args[index][(Switch.Length + 1)..];
            }
        }

        return null;
    }
}
