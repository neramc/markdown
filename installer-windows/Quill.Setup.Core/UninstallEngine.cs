namespace Quill.Setup.Core;

/// <summary>What an uninstall managed to remove, and what it could not.</summary>
/// <param name="FilesRemoved">Count of payload files deleted.</param>
/// <param name="FilesLeftBehind">Files that could not be deleted, with the reason.</param>
/// <param name="InstallRootRemoved">Whether the install root itself is gone.</param>
public sealed record UninstallResult(
    int FilesRemoved,
    IReadOnlyList<string> FilesLeftBehind,
    bool InstallRootRemoved)
{
    /// <summary>Whether everything the manifest listed is gone.</summary>
    public bool Complete => FilesLeftBehind.Count == 0;
}

/// <summary>
/// Reverses an installation using the manifest it left behind.
/// </summary>
/// <remarks>
/// The order is the install order backwards: registrations first, then files, then directories
/// deepest-last. Unregistering before deleting means a shortcut or association never briefly points
/// at a file that has already gone — the state a user hits if they open the Start menu while an
/// uninstall is running.
/// </remarks>
public sealed class UninstallEngine(IPlatformIntegration platform)
{
    private readonly IPlatformIntegration _platform =
        platform ?? throw new ArgumentNullException(nameof(platform));

    /// <summary>Reads the manifest that sits next to an installation.</summary>
    /// <exception cref="FileNotFoundException">There is no manifest to reverse.</exception>
    public static async Task<InstallManifest> LoadManifestAsync(
        string installRoot,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(installRoot);

        var path = Path.Combine(installRoot, ProductInfo.ManifestFileName);
        if (!File.Exists(path))
        {
            throw new FileNotFoundException(
                $"No installation manifest at '{path}'. Without it the uninstaller cannot tell which " +
                "files belong to Quill, and it will not guess.",
                path);
        }

        var json = await File.ReadAllTextAsync(path, cancellationToken).ConfigureAwait(false);
        return InstallManifest.FromJson(json);
    }

    /// <summary>Removes everything <paramref name="manifest"/> records.</summary>
    /// <param name="manifest">The manifest written at install time.</param>
    /// <param name="keepUserData">Reserved for settings kept outside the install root.</param>
    /// <param name="progress">Progress sink.</param>
    /// <param name="cancellationToken">Cancels the removal.</param>
    public Task<UninstallResult> UninstallAsync(
        InstallManifest manifest,
        bool keepUserData = true,
        IProgress<InstallProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        _ = keepUserData;

        progress?.Report(new InstallProgress("Removing Windows registrations", 0, manifest.Files.Count));

        _platform.DeleteUninstallEntry(manifest.Scope);

        foreach (var extension in manifest.FileAssociations)
        {
            cancellationToken.ThrowIfCancellationRequested();
            _platform.UnregisterFileAssociation(manifest.Scope, extension, ProductInfo.ProgId);
        }

        if (!string.IsNullOrEmpty(manifest.PathEntry))
        {
            _platform.RemoveFromPath(manifest.Scope, manifest.PathEntry);
        }

        foreach (var shortcut in manifest.Shortcuts)
        {
            cancellationToken.ThrowIfCancellationRequested();
            _platform.DeleteShortcut(shortcut);
        }

        var root = manifest.InstallRoot;
        var removed = 0;
        var leftBehind = new List<string>();

        foreach (var relative in manifest.Files)
        {
            cancellationToken.ThrowIfCancellationRequested();
            progress?.Report(new InstallProgress($"Removing {relative}", removed, manifest.Files.Count));

            string absolute;
            try
            {
                absolute = PayloadPath.ResolveWithin(root, relative);
            }
            catch (InvalidDataException)
            {
                // A manifest entry pointing outside the install root means a tampered or corrupt
                // manifest. Skipping it is the only safe response — deleting it is exactly the
                // damage the check exists to prevent.
                leftBehind.Add($"{relative} (outside the installation folder)");
                continue;
            }

            try
            {
                if (File.Exists(absolute))
                {
                    File.Delete(absolute);
                }

                removed++;
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
            {
                // A file the user still has open is the normal case here, and it must not abort the
                // rest of the removal.
                leftBehind.Add($"{relative} ({exception.Message})");
            }
        }

        // The manifest itself is not in Files: it was written after extraction.
        TryDelete(Path.Combine(root, ProductInfo.ManifestFileName), leftBehind);

        RemoveDirectories(manifest, root);

        var rootRemoved = TryRemoveDirectoryIfEmpty(root);
        if (!rootRemoved && Directory.Exists(root))
        {
            // The uninstaller is running from inside the folder it is deleting, so the last of it
            // has to be removed by something that outlives this process.
            _platform.ScheduleSelfDelete(root);
        }

        _platform.NotifyShellOfChanges();
        progress?.Report(new InstallProgress("Finished", manifest.Files.Count, manifest.Files.Count));

        return Task.FromResult(new UninstallResult(removed, leftBehind, rootRemoved));
    }

    private static void RemoveDirectories(InstallManifest manifest, string root)
    {
        // Deepest first, so a parent is only attempted once its children are gone.
        var directories = manifest.Directories
            .OrderByDescending(directory => directory.Count(character => character == '/'))
            .ThenByDescending(directory => directory, StringComparer.Ordinal);

        foreach (var relative in directories)
        {
            string absolute;
            try
            {
                absolute = PayloadPath.ResolveWithin(root, relative);
            }
            catch (InvalidDataException)
            {
                continue;
            }

            TryRemoveDirectoryIfEmpty(absolute);
        }
    }

    private static void TryDelete(string path, List<string> leftBehind)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            leftBehind.Add($"{Path.GetFileName(path)} ({exception.Message})");
        }
    }

    /// <summary>
    /// Deletes a directory only when nothing is left in it.
    /// </summary>
    /// <remarks>
    /// Never recursive. A user who installed into an existing folder that also held their own files
    /// keeps those files; the alternative deletes data the installer never created.
    /// </remarks>
    private static bool TryRemoveDirectoryIfEmpty(string directory)
    {
        try
        {
            if (!Directory.Exists(directory))
            {
                return true;
            }

            if (Directory.EnumerateFileSystemEntries(directory).Any())
            {
                return false;
            }

            Directory.Delete(directory);
            return true;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }
}
