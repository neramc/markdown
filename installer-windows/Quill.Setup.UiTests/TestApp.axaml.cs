using Avalonia;
using Avalonia.Headless;
using Avalonia.Markup.Xaml;
using Quill.Setup.UiTests;

[assembly: AvaloniaTestApplication(typeof(TestAppBuilder))]

namespace Quill.Setup.UiTests;

/// <summary>
/// The application the headless tests run inside.
/// </summary>
/// <remarks>
/// It merges both executables' palettes so their real windows can be constructed and rendered here
/// rather than reimplemented.
/// </remarks>
public sealed partial class TestApp : Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);
}

/// <summary>Wires the headless platform to the Skia renderer, so frames can actually be captured.</summary>
public static class TestAppBuilder
{
    public static AppBuilder BuildAvaloniaApp() => AppBuilder
        .Configure<TestApp>()
        .UseSkia()
        // UseHeadlessDrawing: false is the switch that turns this from a layout-only harness into
        // one that rasterises. Without it CaptureRenderedFrame returns nothing.
        .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
        .WithInterFont();
}
