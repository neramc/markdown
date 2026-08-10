using Avalonia.Controls;
using Avalonia.Markup.Xaml;
using Quill.Installer.ViewModels;

namespace Quill.Installer.Views;

/// <summary>The wizard window.</summary>
public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        Opened += OnOpened;
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    /// <summary>
    /// Kicks off an automatic install for the elevated relaunch.
    /// </summary>
    /// <remarks>
    /// Deliberately an async void handler — that is the one signature Avalonia's event requires —
    /// so the body is wrapped: an exception escaping here would terminate the process rather than
    /// land on the wizard's error page.
    /// </remarks>
    private async void OnOpened(object? sender, EventArgs e)
    {
        try
        {
            if (DataContext is MainWindowViewModel { AutoStart: true } viewModel)
            {
                await viewModel.StartInstallAsync();
            }
        }
        catch (Exception exception)
        {
            if (DataContext is MainWindowViewModel viewModel)
            {
                viewModel.ErrorMessage = exception.Message;
            }
        }
    }
}
