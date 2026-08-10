using Quill.Setup.Core;

namespace Quill.Setup.Tests;

/// <summary>
/// A throwaway directory tree holding a fake app image, its payload archive and the simulated
/// Windows folders the dry-run integration writes into.
/// </summary>
/// <remarks>
/// Every test gets its own, so nothing that leaks between runs can make one pass because another
/// ran first.
/// </remarks>
public sealed class TemporaryWorkspace : IDisposable
{
    private bool _disposed;

    public TemporaryWorkspace()
    {
        Root = Path.Combine(Path.GetTempPath(), $"quill-setup-tests-{Guid.NewGuid():N}");
        SourceImage = Path.Combine(Root, "app-image");
        PayloadArchive = Path.Combine(Root, "payload.zip");
        PlatformRoot = Path.Combine(Root, "windows");
        InstallRoot = Path.Combine(Root, "install", "Quill");

        Directory.CreateDirectory(SourceImage);
        Directory.CreateDirectory(PlatformRoot);
    }

    /// <summary>The workspace root; everything else lives underneath it.</summary>
    public string Root { get; }

    /// <summary>Stand-in for the app image the Gradle build produces.</summary>
    public string SourceImage { get; }

    /// <summary>Where <see cref="BuildPayloadAsync"/> writes the archive.</summary>
    public string PayloadArchive { get; }

    /// <summary>Root the dry-run integration hangs its simulated well-known folders off.</summary>
    public string PlatformRoot { get; }

    /// <summary>A target directory for installations.</summary>
    public string InstallRoot { get; }

    /// <summary>Writes a file into the fake app image, creating parent directories.</summary>
    public void AddSourceFile(string relativePath, string contents)
    {
        var absolute = Path.Combine(SourceImage, relativePath.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(absolute)!);
        File.WriteAllText(absolute, contents);
    }

    /// <summary>Populates the image with a plausible jpackage layout: launcher, runtime, resources.</summary>
    public void AddDefaultAppImage()
    {
        AddSourceFile("bin/Quill.exe", "MZ fake launcher");
        AddSourceFile("lib/app/quill-app.jar", "PK fake jar");
        AddSourceFile("lib/app/quill_core.dll", "fake native engine");
        AddSourceFile("lib/runtime/bin/java.exe", "fake runtime");
        AddSourceFile("lib/runtime/lib/modules", "fake modules");
        AddSourceFile("README.txt", "Quill");
    }

    /// <summary>Packs the current image into <see cref="PayloadArchive"/>.</summary>
    public Task<PayloadIndex> BuildPayloadAsync(string version = "1.2.3") =>
        PayloadBuilder.CreateAsync(SourceImage, PayloadArchive, version);

    /// <summary>Opens the payload archive for reading.</summary>
    public FileStream OpenPayload() => File.OpenRead(PayloadArchive);

    /// <summary>A dry-run integration rooted in this workspace.</summary>
    public DryRunPlatformIntegration CreatePlatform(bool isElevated = false) =>
        new(PlatformRoot, isElevated);

    /// <summary>Options installing the default image into <see cref="InstallRoot"/>.</summary>
    public InstallOptions Options(
        InstallScope scope = InstallScope.CurrentUser,
        bool startMenu = true,
        bool desktop = false,
        bool associate = true,
        bool path = false) =>
        new(scope, InstallRoot, startMenu, desktop, associate, path);

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        try
        {
            if (Directory.Exists(Root))
            {
                Directory.Delete(Root, recursive: true);
            }
        }
        catch (IOException)
        {
            // A leftover temp directory is not worth failing a green test run over.
        }
    }
}
