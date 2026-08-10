using Avalonia.Controls;
using Avalonia.Markup.Xaml;
using Quill.Uninstaller.ViewModels;

namespace Quill.Uninstaller.Views;

/// <summary>The uninstaller window.</summary>
public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        Opened += OnOpened;
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    /// <summary>
    /// Reads the manifest once the window exists, so a failure has somewhere to be shown.
    /// </summary>
    /// <remarks>
    /// async void because that is the signature Avalonia's event demands; the body is fully wrapped
    /// so nothing escapes to kill the process.
    /// </remarks>
    private async void OnOpened(object? sender, EventArgs e)
    {
        try
        {
            if (DataContext is MainWindowViewModel viewModel)
            {
                await viewModel.LoadAsync();
            }
        }
        catch (Exception exception)
        {
            if (DataContext is MainWindowViewModel viewModel)
            {
                viewModel.Headline = "Quill could not be removed";
                viewModel.Detail = exception.Message;
                viewModel.Stage = UninstallStage.Finished;
            }
        }
    }
}
