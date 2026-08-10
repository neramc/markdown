using Avalonia;
using Quill.Setup.Core;
using Quill.Uninstaller.ViewModels;

namespace Quill.Uninstaller;

/// <summary>Entry point for QuillUninstall.exe.</summary>
internal static class Program
{
    /// <summary>
    /// Removes the installation this executable sits inside.
    /// </summary>
    /// <remarks>
    /// The install root is the uninstaller's own directory, not a path passed in. That is what makes
    /// the registered <c>UninstallString</c> a bare command with no arguments, and it means the
    /// uninstaller cannot be pointed at some other directory by accident.
    /// </remarks>
    [STAThread]
    public static int Main(string[] args)
    {
        var command = SetupCommandLine.Parse(args);
        var installRoot = ResolveInstallRoot(args);

        if (command.Silent)
        {
            return RunSilent(installRoot, args).GetAwaiter().GetResult();
        }

        App.InstallRoot = installRoot;
        App.DryRunRoot = ReadSwitch(args, "--dry-run-root");

        return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
    }

    /// <summary>Avalonia's builder; also used by the design-time tooling.</summary>
    public static AppBuilder BuildAvaloniaApp() => AppBuilder
        .Configure<App>()
        .UsePlatformDetect()
        .WithInterFont()
        .LogToTrace();

    private static async Task<int> RunSilent(string installRoot, IReadOnlyList<string> args)
    {
        var platform = PlatformIntegrationFactory.Create(ReadSwitch(args, "--dry-run-root"));
        var viewModel = new MainWindowViewModel(platform, installRoot);

        await viewModel.LoadAsync();
        var result = await viewModel.RemoveAsync();

        if (result is null)
        {
            await Console.Error.WriteLineAsync(viewModel.Detail);
            return 1;
        }

        Console.WriteLine($"Removed {result.FilesRemoved} file(s) from {installRoot}.");

        // Files left behind are reported but not treated as failure: Apps & features would otherwise
        // show an error for the ordinary case of the application still being open.
        foreach (var leftover in result.FilesLeftBehind)
        {
            await Console.Error.WriteLineAsync($"left behind: {leftover}");
        }

        return 0;
    }

    /// <summary>
    /// The directory holding this executable, unless <c>--install-root</c> overrides it.
    /// </summary>
    /// <remarks>
    /// The override exists for tests and for the post-uninstall cleanup path; the shipped registry
    /// command never uses it.
    /// </remarks>
    private static string ResolveInstallRoot(IReadOnlyList<string> args)
    {
        if (ReadSwitch(args, "--install-root") is { } explicitRoot && !string.IsNullOrWhiteSpace(explicitRoot))
        {
            return Path.GetFullPath(explicitRoot);
        }

        var executable = Environment.ProcessPath;
        var directory = string.IsNullOrEmpty(executable) ? null : Path.GetDirectoryName(executable);
        return directory ?? Directory.GetCurrentDirectory();
    }

    private static string? ReadSwitch(IReadOnlyList<string> args, string name)
    {
        for (var index = 0; index < args.Count; index++)
        {
            if (string.Equals(args[index], name, StringComparison.OrdinalIgnoreCase) && index + 1 < args.Count)
            {
                return args[index + 1];
            }

            if (args[index].StartsWith(name + "=", StringComparison.OrdinalIgnoreCase))
            {
                return args[index][(name.Length + 1)..];
            }
        }

        return null;
    }
}
