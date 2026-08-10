using System.Reflection;

namespace Quill.Setup.Core;

/// <summary>Where the installer found its payload.</summary>
public enum PayloadOrigin
{
    /// <summary>Compiled into the executable — the shipped configuration.</summary>
    Embedded,

    /// <summary>Supplied on the command line, for development and CI smoke runs.</summary>
    ExternalFile,

    /// <summary>Nothing was found.</summary>
    None,
}

/// <summary>
/// Locates the application payload.
/// </summary>
/// <remarks>
/// The shipped installer carries the payload as an embedded resource so it installs with no network
/// and no side-by-side files. A build from a plain checkout has no payload, and rather than failing
/// to compile, the wizard runs and says so — which is also what makes the UI developable without a
/// full Gradle build sitting behind it.
/// </remarks>
public static class PayloadSource
{
    /// <summary>Resource name the build embeds the archive under.</summary>
    public const string ResourceName = "Quill.Installer.Payload.zip";

    /// <summary>Command-line switch pointing at an archive on disk.</summary>
    public const string FileSwitch = "--payload";

    /// <summary>
    /// Opens the payload.
    /// </summary>
    /// <param name="assembly">Assembly whose resources are searched, usually the entry assembly.</param>
    /// <param name="arguments">Process arguments, scanned for <see cref="FileSwitch"/>.</param>
    /// <returns>An open stream and where it came from; the stream is null when nothing was found.</returns>
    public static (Stream? Stream, PayloadOrigin Origin) Open(Assembly assembly, IReadOnlyList<string> arguments)
    {
        ArgumentNullException.ThrowIfNull(assembly);
        ArgumentNullException.ThrowIfNull(arguments);

        // An explicit path wins, so a developer can test against a real app image without rebuilding
        // the installer around it.
        if (TryGetFilePath(arguments) is { } path && File.Exists(path))
        {
            return (File.OpenRead(path), PayloadOrigin.ExternalFile);
        }

        var resource = assembly.GetManifestResourceStream(ResourceName);
        if (resource is not null)
        {
            // The engine seeks the stream back to zero between reading the index and extracting, and
            // a manifest resource stream is seekable, so no copy is needed.
            return (resource, PayloadOrigin.Embedded);
        }

        return (null, PayloadOrigin.None);
    }

    /// <summary>Returns the value of <see cref="FileSwitch"/>, or null.</summary>
    public static string? TryGetFilePath(IReadOnlyList<string> arguments)
    {
        ArgumentNullException.ThrowIfNull(arguments);

        for (var index = 0; index < arguments.Count; index++)
        {
            var argument = arguments[index];
            if (string.Equals(argument, FileSwitch, StringComparison.OrdinalIgnoreCase) &&
                index + 1 < arguments.Count)
            {
                return arguments[index + 1];
            }

            if (argument.StartsWith(FileSwitch + "=", StringComparison.OrdinalIgnoreCase))
            {
                return argument[(FileSwitch.Length + 1)..];
            }
        }

        return null;
    }
}
