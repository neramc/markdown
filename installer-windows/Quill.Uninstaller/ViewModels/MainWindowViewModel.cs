using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Quill.Setup.Core;

namespace Quill.Uninstaller.ViewModels;

/// <summary>The uninstaller's three states.</summary>
public enum UninstallStage
{
    Confirm,
    Removing,
    Finished,
}

/// <summary>
/// Drives the uninstaller: confirm, remove, report.
/// </summary>
/// <remarks>
/// Everything it knows comes from the manifest the installer wrote next to the application. If that
/// file is unreadable the uninstaller says so and stops rather than falling back on heuristics —
/// a program that deletes files it is only fairly sure about is not one to run with a wildcard.
/// </remarks>
public sealed partial class MainWindowViewModel : ObservableObject
{
    private readonly IPlatformIntegration _platform;
    private readonly CancellationTokenSource _cancellation = new();
    private InstallManifest? _manifest;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsConfirm))]
    [NotifyPropertyChangedFor(nameof(IsRemoving))]
    [NotifyPropertyChangedFor(nameof(IsFinished))]
    private UninstallStage _stage = UninstallStage.Confirm;

    [ObservableProperty]
    private string _headline = "Remove Quill?";

    [ObservableProperty]
    private string _detail = string.Empty;

    [ObservableProperty]
    private double _progressFraction;

    [ObservableProperty]
    private string _progressStage = string.Empty;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasLeftovers))]
    private string? _leftovers;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanRemove))]
    private bool _isBusy;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanRemove))]
    private bool _isLoaded;

    public MainWindowViewModel(IPlatformIntegration platform, string installRoot)
    {
        _platform = platform ?? throw new ArgumentNullException(nameof(platform));
        InstallRoot = installRoot;
    }

    /// <summary>The installation being removed.</summary>
    public string InstallRoot { get; }

    public bool IsConfirm => Stage == UninstallStage.Confirm;

    public bool IsRemoving => Stage == UninstallStage.Removing;

    public bool IsFinished => Stage == UninstallStage.Finished;

    public bool HasLeftovers => !string.IsNullOrEmpty(Leftovers);

    public bool CanRemove => IsLoaded && !IsBusy;

    /// <summary>Raised when the window should close.</summary>
    public event EventHandler? CloseRequested;

    /// <summary>Reads the manifest and fills in what will be removed.</summary>
    public async Task LoadAsync()
    {
        try
        {
            _manifest = await UninstallEngine.LoadManifestAsync(InstallRoot, _cancellation.Token)
                .ConfigureAwait(true);

            Detail =
                $"Quill {_manifest.Version} will be removed from {_manifest.InstallRoot}. " +
                $"{_manifest.Files.Count} files, {_manifest.Shortcuts.Count} shortcut(s) and its " +
                "Windows registrations will be deleted. Your documents are not touched.";
            IsLoaded = true;
        }
        catch (Exception exception) when (exception is FileNotFoundException or InvalidDataException
                                              or IOException or UnauthorizedAccessException)
        {
            Headline = "Nothing to remove";
            Detail = exception.Message;
            Stage = UninstallStage.Finished;
        }
    }

    /// <summary>Removes the installation. Also the entry point for a silent <c>/S</c> run.</summary>
    public async Task<UninstallResult?> RemoveAsync()
    {
        if (_manifest is null)
        {
            return null;
        }

        Stage = UninstallStage.Removing;
        IsBusy = true;

        try
        {
            var progress = new Progress<InstallProgress>(report =>
            {
                ProgressStage = report.Stage;
                ProgressFraction = report.Fraction;
            });

            var result = await new UninstallEngine(_platform)
                .UninstallAsync(_manifest, keepUserData: true, progress, _cancellation.Token)
                .ConfigureAwait(true);

            Headline = result.Complete ? "Quill has been removed" : "Quill has been mostly removed";
            Detail = result.Complete
                ? $"{result.FilesRemoved} files and every Windows registration were deleted."
                : $"{result.FilesRemoved} files were deleted. Some could not be removed and are listed below; " +
                  "they are usually files still open in another program.";

            Leftovers = result.FilesLeftBehind.Count == 0
                ? null
                : string.Join(Environment.NewLine, result.FilesLeftBehind.Take(12));

            Stage = UninstallStage.Finished;
            return result;
        }
        catch (OperationCanceledException)
        {
            CloseRequested?.Invoke(this, EventArgs.Empty);
            return null;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException
                                              or InvalidDataException)
        {
            Headline = "Quill could not be removed";
            Detail = exception.Message;
            Stage = UninstallStage.Finished;
            return null;
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task RemoveClickedAsync() => await RemoveAsync().ConfigureAwait(true);

    [RelayCommand]
    private void Close()
    {
        _cancellation.Cancel();
        CloseRequested?.Invoke(this, EventArgs.Empty);
    }
}
