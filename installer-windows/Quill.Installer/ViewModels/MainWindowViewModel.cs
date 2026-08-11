using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Reflection;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Quill.Setup.Core;

namespace Quill.Installer.ViewModels;

/// <summary>The wizard's pages, in the order they appear.</summary>
public enum WizardStep
{
    Welcome,
    License,
    Location,
    Components,
    Progress,
    Finish,
}

/// <summary>
/// Drives the whole wizard: page order, the choices, and the install itself.
/// </summary>
/// <remarks>
/// One view model rather than one per page, because every page reads or writes the same handful of
/// values and the navigation rules depend on several of them at once. Splitting them would mean a
/// shared state object plus six wrappers around it.
/// </remarks>
public sealed partial class MainWindowViewModel : ObservableObject
{
    private readonly IPlatformIntegration _platform;
    private readonly IReadOnlyList<string> _arguments;
    private readonly Assembly _payloadAssembly;
    private readonly CancellationTokenSource _cancellation = new();

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanGoBack))]
    [NotifyPropertyChangedFor(nameof(CanGoNext))]
    [NotifyPropertyChangedFor(nameof(NextLabel))]
    [NotifyPropertyChangedFor(nameof(IsWelcome))]
    [NotifyPropertyChangedFor(nameof(IsLicense))]
    [NotifyPropertyChangedFor(nameof(IsLocation))]
    [NotifyPropertyChangedFor(nameof(IsComponents))]
    [NotifyPropertyChangedFor(nameof(IsProgress))]
    [NotifyPropertyChangedFor(nameof(IsFinish))]
    [NotifyPropertyChangedFor(nameof(IsNavigationVisible))]
    private WizardStep _step = WizardStep.Welcome;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanGoNext))]
    private bool _licenseAccepted;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanGoNext))]
    [NotifyPropertyChangedFor(nameof(TargetDirectoryError))]
    private string _targetDirectory = string.Empty;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(RequiresElevation))]
    private bool _allUsers;

    [ObservableProperty]
    private bool _createStartMenuShortcut = true;

    [ObservableProperty]
    private bool _createDesktopShortcut;

    [ObservableProperty]
    private bool _associateMarkdown = true;

    [ObservableProperty]
    private bool _addToPath;

    [ObservableProperty]
    private double _progressFraction;

    [ObservableProperty]
    private string _progressStage = "Preparing…";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasError))]
    private string? _errorMessage;

    [ObservableProperty]
    private string _finishTitle = "Quill is installed";

    [ObservableProperty]
    private string _finishDetail = string.Empty;

    [ObservableProperty]
    private bool _launchOnFinish = true;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanGoNext))]
    private bool _isBusy;

    /// <param name="platform">The integration to install through.</param>
    /// <param name="arguments">Process arguments, used to find an external payload.</param>
    /// <param name="payloadAssembly">
    /// Assembly searched for the embedded payload. Defaults to this one, which is the shipped
    /// arrangement; tests pass their own so the "no payload" state is reachable whether or not a
    /// payload happens to have been built into this assembly.
    /// </param>
    public MainWindowViewModel(
        IPlatformIntegration platform,
        IReadOnlyList<string>? arguments = null,
        Assembly? payloadAssembly = null)
    {
        _platform = platform ?? throw new ArgumentNullException(nameof(platform));
        _arguments = arguments ?? [];
        _payloadAssembly = payloadAssembly ?? Assembly.GetExecutingAssembly();

        TargetDirectory = _platform.GetDefaultInstallRoot(InstallScope.CurrentUser);
        LicenseText = LoadLicense();

        var (payload, origin) = PayloadSource.Open(_payloadAssembly, _arguments);
        payload?.Dispose();
        PayloadOrigin = origin;

        Existing = _platform.FindInstalledProduct(InstallScope.CurrentUser)
            ?? _platform.FindInstalledProduct(InstallScope.AllUsers);
    }

    /// <summary>Full licence text, shown on the licence page.</summary>
    public string LicenseText { get; }

    /// <summary>Where the payload came from, which the welcome page reports.</summary>
    public PayloadOrigin PayloadOrigin { get; }

    /// <summary>A previous installation, when one was found.</summary>
    public InstalledProduct? Existing { get; }

    /// <summary>Version stamped into this installer.</summary>
    public string Version =>
        Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.1.0";

    /// <summary>Steps shown in the sidebar, so the user can see where they are.</summary>
    public ObservableCollection<string> StepNames { get; } =
        ["Welcome", "License", "Location", "Components", "Install", "Finish"];

    public bool IsWelcome => Step == WizardStep.Welcome;

    public bool IsLicense => Step == WizardStep.License;

    public bool IsLocation => Step == WizardStep.Location;

    public bool IsComponents => Step == WizardStep.Components;

    public bool IsProgress => Step == WizardStep.Progress;

    public bool IsFinish => Step == WizardStep.Finish;

    /// <summary>The footer is hidden while installing, so there is nothing to click mid-copy.</summary>
    public bool IsNavigationVisible => Step != WizardStep.Progress;

    public bool HasError => !string.IsNullOrEmpty(ErrorMessage);

    /// <summary>Whether an all-users install still needs a UAC round trip.</summary>
    public bool RequiresElevation => AllUsers && !_platform.IsElevated;

    /// <summary>Whether the payload is missing, in which case installing is impossible.</summary>
    public bool HasPayload => PayloadOrigin != PayloadOrigin.None;

    /// <summary>Headline for the welcome page.</summary>
    public string WelcomeHeadline => Existing is null
        ? "Install Quill"
        : $"Update Quill {Existing.DisplayVersion}";

    /// <summary>What the primary button says on this page.</summary>
    public string NextLabel => Step switch
    {
        WizardStep.Components => Existing is null ? "Install" : "Update",
        WizardStep.Finish => "Close",
        _ => "Next",
    };

    public bool CanGoBack => Step is WizardStep.License or WizardStep.Location or WizardStep.Components;

    public bool CanGoNext => !IsBusy && Step switch
    {
        WizardStep.Welcome => HasPayload,
        WizardStep.License => LicenseAccepted,
        WizardStep.Location => TargetDirectoryError is null,
        _ => true,
    };

    /// <summary>The problem with the chosen folder, or null when it is usable.</summary>
    public string? TargetDirectoryError => BuildOptions().Validate();

    /// <summary>Raised when the user finishes or cancels; the window closes on it.</summary>
    public event EventHandler? CloseRequested;

    [RelayCommand]
    private void Back()
    {
        if (CanGoBack)
        {
            Step = (WizardStep)((int)Step - 1);
        }
    }

    [RelayCommand]
    private async Task NextAsync()
    {
        if (!CanGoNext)
        {
            return;
        }

        switch (Step)
        {
            case WizardStep.Components:
                await RunInstallAsync().ConfigureAwait(true);
                break;

            case WizardStep.Finish:
                Finish();
                break;

            default:
                Step = (WizardStep)((int)Step + 1);
                break;
        }
    }

    [RelayCommand]
    private void Cancel()
    {
        _cancellation.Cancel();
        CloseRequested?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Switches the target folder to the default for the currently selected scope.</summary>
    [RelayCommand]
    private void UseDefaultDirectory() =>
        TargetDirectory = _platform.GetDefaultInstallRoot(AllUsers ? InstallScope.AllUsers : InstallScope.CurrentUser);

    partial void OnAllUsersChanged(bool value)
    {
        // Following the scope keeps the two fields consistent unless the user typed their own path,
        // which is detected by the current value still being one of the two defaults.
        var currentUserDefault = _platform.GetDefaultInstallRoot(InstallScope.CurrentUser);
        var allUsersDefault = _platform.GetDefaultInstallRoot(InstallScope.AllUsers);

        if (string.Equals(TargetDirectory, currentUserDefault, StringComparison.OrdinalIgnoreCase) ||
            string.Equals(TargetDirectory, allUsersDefault, StringComparison.OrdinalIgnoreCase))
        {
            TargetDirectory = value ? allUsersDefault : currentUserDefault;
        }
    }

    /// <summary>Snapshots the current choices.</summary>
    public InstallOptions BuildOptions() => new(
        Scope: AllUsers ? InstallScope.AllUsers : InstallScope.CurrentUser,
        TargetDirectory: TargetDirectory,
        CreateStartMenuShortcut: CreateStartMenuShortcut,
        CreateDesktopShortcut: CreateDesktopShortcut,
        AssociateMarkdown: AssociateMarkdown,
        AddToPath: AddToPath);

    private async Task RunInstallAsync()
    {
        Step = WizardStep.Progress;
        IsBusy = true;
        ErrorMessage = null;

        try
        {
            if (RequiresElevation)
            {
                // The current process cannot write to Program Files, so it hands the same choices to
                // an elevated copy of itself and steps aside.
                var relaunched = await _platform
                    .RelaunchElevatedAsync(BuildElevatedArguments(), _cancellation.Token)
                    .ConfigureAwait(true);

                if (relaunched)
                {
                    CloseRequested?.Invoke(this, EventArgs.Empty);
                    return;
                }

                throw new InvalidOperationException(
                    "Administrator rights are needed to install for all users, and the request was " +
                    "declined. Go back and choose the per-user installation instead.");
            }

            var (payload, _) = PayloadSource.Open(_payloadAssembly, _arguments);
            if (payload is null)
            {
                throw new InvalidOperationException(
                    "This installer was built without an application payload, so there is nothing " +
                    "to install.");
            }

            await using (payload)
            {
                var progress = new Progress<InstallProgress>(report =>
                {
                    ProgressStage = report.Stage;
                    ProgressFraction = report.Fraction;
                });

                var result = await new InstallEngine(_platform)
                    .InstallAsync(payload, BuildOptions(), progress, _cancellation.Token)
                    .ConfigureAwait(true);

                InstalledExecutable = result.ExecutablePath;
                FinishTitle = result.WasUpgrade ? "Quill is up to date" : "Quill is installed";
                FinishDetail =
                    $"Version {result.Manifest.Version} is in {result.Manifest.InstallRoot}. " +
                    $"{result.Manifest.Files.Count} files were copied and verified.";
            }

            Step = WizardStep.Finish;
        }
        catch (OperationCanceledException)
        {
            // Cancellation is the user's decision, not an error to report back at them.
            CloseRequested?.Invoke(this, EventArgs.Empty);
        }
        catch (Exception exception) when (exception is InvalidOperationException or InvalidDataException
                                              or IOException or UnauthorizedAccessException)
        {
            ErrorMessage = exception.Message;
            FinishTitle = "Quill was not installed";
            FinishDetail = exception.Message;
            Step = WizardStep.Finish;
        }
        finally
        {
            IsBusy = false;
        }
    }

    /// <summary>Path of the launcher that was installed, used by the finish page.</summary>
    public string? InstalledExecutable { get; private set; }

    /// <summary>
    /// Whether the window should begin installing as soon as it appears.
    /// </summary>
    /// <remarks>
    /// Set on the elevated relaunch, which already carries every answer the user gave the first
    /// copy of the wizard and would otherwise make them fill it in twice.
    /// </remarks>
    public bool AutoStart { get; set; }

    /// <summary>Starts the install without any further interaction. Used when <see cref="AutoStart"/> is set.</summary>
    public Task StartInstallAsync() => RunInstallAsync();

    private List<string> BuildElevatedArguments()
    {
        var arguments = new List<string>
        {
            "--all-users",
            "--target", TargetDirectory,
        };

        if (CreateStartMenuShortcut)
        {
            arguments.Add("--start-menu");
        }

        if (CreateDesktopShortcut)
        {
            arguments.Add("--desktop");
        }

        if (AssociateMarkdown)
        {
            arguments.Add("--associate");
        }

        if (AddToPath)
        {
            arguments.Add("--add-to-path");
        }

        if (PayloadSource.TryGetFilePath(_arguments) is { } payloadPath)
        {
            arguments.Add(PayloadSource.FileSwitch);
            arguments.Add(payloadPath);
        }

        return arguments;
    }

    private void Finish()
    {
        if (LaunchOnFinish && !HasError && InstalledExecutable is { } executable && File.Exists(executable))
        {
            try
            {
                using var _ = Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true });
            }
            catch (Exception exception) when (exception is System.ComponentModel.Win32Exception or IOException)
            {
                // Failing to launch must not turn a successful installation into an error dialog.
            }
        }

        CloseRequested?.Invoke(this, EventArgs.Empty);
    }

    private static string LoadLicense()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Quill.Installer.LICENSE");
        if (stream is null)
        {
            return "License text unavailable.";
        }

        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }
}
