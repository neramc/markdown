using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Quill.Setup.Core;
using Quill.Uninstaller.ViewModels;
using Quill.Uninstaller.Views;

namespace Quill.Uninstaller;

/// <summary>The Avalonia application for QuillUninstall.exe.</summary>
public sealed partial class App : Application
{
    /// <summary>Installation root to remove, resolved by <see cref="Program"/>.</summary>
    public static string InstallRoot { get; set; } = string.Empty;

    /// <summary>Where the dry-run integration roots its simulated folders, for non-Windows runs.</summary>
    public static string? DryRunRoot { get; set; }

    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var viewModel = new MainWindowViewModel(PlatformIntegrationFactory.Create(DryRunRoot), InstallRoot);
            var window = new MainWindow { DataContext = viewModel };
            viewModel.CloseRequested += (_, _) => window.Close();
            desktop.MainWindow = window;
        }

        base.OnFrameworkInitializationCompleted();
    }
}
