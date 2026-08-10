using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Quill.Installer.ViewModels;
using Quill.Installer.Views;
using Quill.Setup.Core;

namespace Quill.Installer;

/// <summary>The Avalonia application: builds the view model and shows the wizard.</summary>
public sealed partial class App : Application
{
    /// <summary>Arguments the process was started with, set by <see cref="Program"/>.</summary>
    public static IReadOnlyList<string> Arguments { get; set; } = [];

    /// <summary>Where the dry-run integration roots its simulated folders, for non-Windows runs.</summary>
    public static string? DryRunRoot { get; set; }

    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var platform = PlatformIntegrationFactory.Create(DryRunRoot);
            var command = SetupCommandLine.Parse(Arguments);
            var viewModel = new MainWindowViewModel(platform, Arguments);

            ApplyCommandLine(viewModel, command, platform);

            var window = new MainWindow { DataContext = viewModel };
            viewModel.CloseRequested += (_, _) => window.Close();
            desktop.MainWindow = window;
        }

        base.OnFrameworkInitializationCompleted();
    }

    /// <summary>
    /// Pre-fills the wizard from the command line.
    /// </summary>
    /// <remarks>
    /// This is what makes the elevated relaunch continue the user's session rather than restart it:
    /// the elevated copy receives exactly the choices already made and goes straight to installing,
    /// so the user answers the wizard once and sees a UAC prompt once.
    /// </remarks>
    private static void ApplyCommandLine(
        MainWindowViewModel viewModel,
        SetupCommandLine command,
        IPlatformIntegration platform)
    {
        var options = command.ToInstallOptions(platform);

        viewModel.AllUsers = command.AllUsers;
        viewModel.TargetDirectory = options.TargetDirectory;
        viewModel.CreateStartMenuShortcut = options.CreateStartMenuShortcut;
        viewModel.CreateDesktopShortcut = options.CreateDesktopShortcut;
        viewModel.AssociateMarkdown = options.AssociateMarkdown;
        viewModel.AddToPath = options.AddToPath;

        // An elevated relaunch always arrives with --all-users and a target already chosen.
        viewModel.AutoStart = command.AllUsers && platform.IsElevated;
        viewModel.LicenseAccepted = viewModel.AutoStart;
    }
}
