namespace Quill.Setup.Core;

/// <summary>Chooses the integration that matches the running platform.</summary>
public static class PlatformIntegrationFactory
{
    /// <summary>
    /// Returns the real Windows integration when compiled and running on Windows, and a dry run
    /// rooted at <paramref name="dryRunRoot"/> otherwise.
    /// </summary>
    /// <remarks>
    /// The non-Windows path exists so the wizard can be developed and screenshotted on any machine.
    /// It is not a fallback the shipped installer can reach: the published binary targets
    /// net10.0-windows and runs on Windows, so the first branch is the only live one there.
    /// </remarks>
    public static IPlatformIntegration Create(string? dryRunRoot = null)
    {
#if WINDOWS
        if (OperatingSystem.IsWindows())
        {
            return new Windows.WindowsPlatformIntegration();
        }
#endif

        var root = dryRunRoot ?? Path.Combine(Path.GetTempPath(), "quill-setup-dryrun");
        Directory.CreateDirectory(root);
        return new DryRunPlatformIntegration(root);
    }

    /// <summary>Whether <see cref="Create"/> would return a simulation rather than the real thing.</summary>
    public static bool IsDryRun =>
#if WINDOWS
        !OperatingSystem.IsWindows();
#else
        true;
#endif
}
