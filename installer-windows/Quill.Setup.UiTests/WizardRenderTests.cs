using System.Runtime.InteropServices;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
using Quill.Installer.ViewModels;
using Quill.Setup.Core;

namespace Quill.Setup.UiTests;

/// <summary>
/// Renders the installer wizard and the uninstaller offscreen and checks that they draw.
/// </summary>
/// <remarks>
/// The windows under test are the shipped ones, constructed with the shipped view models against a
/// dry-run integration. What is being verified is that the XAML loads, every resource key resolves,
/// compiled bindings find their properties, and each page produces pixels — the class of failure
/// that only shows up when the window is actually shown, and that a Windows-only manual test would
/// otherwise be the only way to catch.
/// </remarks>
public sealed class WizardRenderTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), $"quill-ui-tests-{Guid.NewGuid():N}");
    private readonly string _outputDirectory;

    public WizardRenderTests()
    {
        Directory.CreateDirectory(_root);
        _outputDirectory = Environment.GetEnvironmentVariable("QUILL_UI_RENDER_DIR")
            ?? Path.Combine(AppContext.BaseDirectory, "ui-renders");
        Directory.CreateDirectory(_outputDirectory);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(_root, recursive: true);
            }
        }
        catch (IOException)
        {
            // A leftover temp directory is not worth failing a green run over.
        }
    }

    [AvaloniaTheory]
    [InlineData(WizardStep.Welcome, "wizard-1-welcome.png")]
    [InlineData(WizardStep.License, "wizard-2-license.png")]
    [InlineData(WizardStep.Location, "wizard-3-location.png")]
    [InlineData(WizardStep.Components, "wizard-4-components.png")]
    [InlineData(WizardStep.Progress, "wizard-5-progress.png")]
    [InlineData(WizardStep.Finish, "wizard-6-finish.png")]
    public void Every_wizard_page_renders(WizardStep step, string fileName)
    {
        var viewModel = CreateInstallerViewModel();
        viewModel.Step = step;

        if (step == WizardStep.Progress)
        {
            viewModel.ProgressFraction = 0.42;
            viewModel.ProgressStage = "Copying lib/runtime/lib/modules";
        }

        if (step == WizardStep.Finish)
        {
            viewModel.FinishDetail = "Version 1.2.3 is in C:\\Users\\you\\AppData\\Local\\Programs\\Quill. "
                + "142 files were copied and verified.";
        }

        var window = new Quill.Installer.Views.MainWindow { DataContext = viewModel };
        var pixels = Render(window, fileName);

        Assert.True(pixels.Distinct > 32, $"{step} drew only {pixels.Distinct} distinct colours");
    }

    [AvaloniaFact]
    public async Task The_welcome_page_reflects_whether_a_payload_is_present()
    {
        // The assembly searched for the embedded payload is injected, so this test gives the same
        // answer whether or not tools/build-installer.sh has left a payload in the installer's own
        // assembly. Reading the shipped assembly here would make the result depend on build order.
        var without = CreateInstallerViewModel();
        Assert.False(without.HasPayload);
        Assert.False(without.InstallCommand.CanExecute(null) && without.HasPayload,
            "the installer must not offer to install nothing");

        var noPayloadWindow = new Quill.Installer.Views.MainWindow { DataContext = without };
        var noPayloadFrame = Render(noPayloadWindow, "wizard-no-payload.png");

        var archive = await BuildPayloadAsync();
        var with = new MainWindowViewModel(
            new DryRunPlatformIntegration(_root),
            [PayloadSource.FileSwitch, archive],
            typeof(WizardRenderTests).Assembly);

        Assert.True(with.HasPayload);
        Assert.Equal(PayloadOrigin.ExternalFile, with.PayloadOrigin);
        Assert.True(with.HasPayload);

        var withPayloadWindow = new Quill.Installer.Views.MainWindow { DataContext = with };
        var withPayloadFrame = Render(withPayloadWindow, "wizard-with-payload.png");

        // The warning panel is the only difference between the two welcome pages.
        Assert.True(
            Difference(noPayloadFrame, withPayloadFrame) > 0.01,
            "the missing-payload warning did not change what is on screen");
    }

    [AvaloniaFact]
    public void The_three_states_of_the_one_screen_are_visibly_different()
    {
        // There are no pages any more -- the installer is one window that becomes the progress view
        // and then the finish view. What still has to hold is that those three look different: a
        // state whose IsVisible binding silently failed would render as one of the others, and a
        // per-state "did it draw" check alone would not notice.
        var viewModel = CreateInstallerViewModel();
        var window = new Quill.Installer.Views.MainWindow { DataContext = viewModel };

        var states = new[] { WizardStep.Welcome, WizardStep.Progress, WizardStep.Finish };
        var frames = new List<Frame>();
        foreach (var state in states)
        {
            viewModel.Step = state;
            frames.Add(Render(window, $"compare-{state}.png"));
        }

        for (var first = 0; first < frames.Count; first++)
        {
            for (var second = first + 1; second < frames.Count; second++)
            {
                Assert.True(
                    Difference(frames[first], frames[second]) > 0.005,
                    $"the {states[first]} and {states[second]} states rendered the same");
            }
        }
    }

    [AvaloniaFact]
    public void Choosing_all_users_switches_the_default_directory()
    {
        var viewModel = CreateInstallerViewModel();
        var platform = new DryRunPlatformIntegration(_root);

        Assert.Equal(platform.GetDefaultInstallRoot(InstallScope.CurrentUser), viewModel.TargetDirectory);

        viewModel.AllUsers = true;
        Assert.Equal(platform.GetDefaultInstallRoot(InstallScope.AllUsers), viewModel.TargetDirectory);

        // A path the user typed themselves is not overwritten when the scope changes.
        viewModel.TargetDirectory = Path.Combine(_root, "custom");
        viewModel.AllUsers = false;
        Assert.Equal(Path.Combine(_root, "custom"), viewModel.TargetDirectory);
    }

    [AvaloniaFact]
    public async Task The_uninstaller_renders_a_real_installation_and_its_result()
    {
        // Against a real installation, not an empty directory: the confirmation page's whole job is
        // to summarise what is about to be deleted, and a page rendered with nothing to delete would
        // prove only that the window opens.
        var platform = new DryRunPlatformIntegration(_root);
        var installRoot = await InstallSampleAsync(platform);

        var viewModel = new Quill.Uninstaller.ViewModels.MainWindowViewModel(platform, installRoot);
        await viewModel.LoadAsync();

        Assert.True(viewModel.IsConfirm);
        Assert.True(viewModel.CanRemove);
        Assert.Contains(installRoot, viewModel.Detail, StringComparison.Ordinal);

        var window = new Quill.Uninstaller.Views.MainWindow { DataContext = viewModel };
        var confirm = Render(window, "uninstaller-1-confirm.png");
        Assert.True(confirm.Distinct > 32, "the uninstaller confirmation rendered blank");

        var result = await viewModel.RemoveAsync();
        Assert.NotNull(result);
        Assert.True(result.Complete, string.Join("; ", result.FilesLeftBehind));

        var finished = Render(window, "uninstaller-2-finished.png");
        Assert.True(Difference(confirm, finished) > 0.01, "the uninstaller result looks like its confirmation");
        Assert.False(Directory.Exists(installRoot));
    }

    [AvaloniaFact]
    public void The_uninstaller_says_so_when_there_is_no_manifest_to_reverse()
    {
        var platform = new DryRunPlatformIntegration(_root);
        var viewModel = new Quill.Uninstaller.ViewModels.MainWindowViewModel(
            platform,
            Path.Combine(_root, "not-an-installation"));

        var window = new Quill.Uninstaller.Views.MainWindow { DataContext = viewModel };
        var frame = Render(window, "uninstaller-0-nothing.png");

        Assert.True(frame.Distinct > 32);
        Assert.True(viewModel.IsFinished);
        Assert.False(viewModel.CanRemove);
    }

    /// <summary>Packs a small app image and installs it, so a test has something real to remove.</summary>
    private async Task<string> InstallSampleAsync(IPlatformIntegration platform)
    {
        var image = Path.Combine(_root, "image");
        Directory.CreateDirectory(Path.Combine(image, "bin"));
        Directory.CreateDirectory(Path.Combine(image, "lib", "app"));
        await File.WriteAllTextAsync(Path.Combine(image, "bin", "Quill.exe"), "MZ fake launcher");
        await File.WriteAllTextAsync(Path.Combine(image, "lib", "app", "quill-app.jar"), "PK fake jar");
        await File.WriteAllTextAsync(Path.Combine(image, "README.txt"), "Quill");

        var archive = Path.Combine(_root, "payload.zip");
        await PayloadBuilder.CreateAsync(image, archive, "1.2.3");

        var installRoot = Path.Combine(_root, "install", "Quill");
        await using var payload = File.OpenRead(archive);
        await new InstallEngine(platform).InstallAsync(
            payload,
            new InstallOptions(InstallScope.CurrentUser, installRoot));

        return installRoot;
    }

    /// <summary>A wizard view model whose payload assembly embeds nothing, deterministically.</summary>
    private MainWindowViewModel CreateInstallerViewModel() =>
        new(new DryRunPlatformIntegration(_root), [], typeof(WizardRenderTests).Assembly);

    /// <summary>Packs a small app image and returns the archive path.</summary>
    private async Task<string> BuildPayloadAsync()
    {
        var image = Path.Combine(_root, "payload-image");
        Directory.CreateDirectory(Path.Combine(image, "bin"));
        await File.WriteAllTextAsync(Path.Combine(image, "bin", "Quill.exe"), "MZ fake launcher");

        var archive = Path.Combine(_root, "wizard-payload.zip");
        await PayloadBuilder.CreateAsync(image, archive, "1.2.3");
        return archive;
    }

    private readonly record struct Frame(int Width, int Height, uint[] Pixels, int Distinct);

    /// <summary>Lays the window out, rasterises it, writes a PNG and returns the pixels.</summary>
    private Frame Render(Window window, string fileName)
    {
        window.Show();

        // Draining the dispatcher runs the layout pass and the render; the wizard has no animation
        // that needs several frames to settle.
        Dispatcher.UIThread.RunJobs();

        var bitmap = window.CaptureRenderedFrame();
        Assert.NotNull(bitmap);

        // The PNG is for a human to look at; the assertions run on the pixels directly, so a change
        // in PNG encoding cannot affect a test result.
        bitmap.Save(Path.Combine(_outputDirectory, fileName), new PngBitmapEncoderOptions());

        return ReadFrame(bitmap);
    }

    private static Frame ReadFrame(WriteableBitmap bitmap)
    {
        using var buffer = bitmap.Lock();

        var width = buffer.Size.Width;
        var height = buffer.Size.Height;
        var bytes = new byte[buffer.RowBytes * height];
        Marshal.Copy(buffer.Address, bytes, 0, bytes.Length);

        var pixels = new uint[width * height];
        var distinct = new HashSet<uint>();

        for (var y = 0; y < height; y++)
        {
            var row = y * buffer.RowBytes;
            for (var x = 0; x < width; x++)
            {
                // The headless Skia surface hands back 32 bits per pixel; only the three colour
                // channels are compared, so premultiplied alpha cannot skew the counts.
                var offset = row + (x * 4);
                var colour = (uint)((bytes[offset + 2] << 16) | (bytes[offset + 1] << 8) | bytes[offset]);
                pixels[(y * width) + x] = colour;

                if (distinct.Count <= 4096)
                {
                    distinct.Add(colour);
                }
            }
        }

        return new Frame(width, height, pixels, distinct.Count);
    }

    /// <summary>Fraction of pixels that differ between two frames of the same size.</summary>
    private static double Difference(Frame first, Frame second)
    {
        Assert.Equal(first.Width, second.Width);
        Assert.Equal(first.Height, second.Height);

        var differing = 0;
        for (var index = 0; index < first.Pixels.Length; index++)
        {
            if (first.Pixels[index] != second.Pixels[index])
            {
                differing++;
            }
        }

        return (double)differing / first.Pixels.Length;
    }
}
