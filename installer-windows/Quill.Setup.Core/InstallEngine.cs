namespace Quill.Setup.Core;

/// <summary>What an install ended up doing.</summary>
/// <param name="Manifest">The manifest written into the install root.</param>
/// <param name="ManifestPath">Absolute path of that manifest.</param>
/// <param name="ExecutablePath">Absolute path of the installed launcher.</param>
/// <param name="WasUpgrade">Whether a previous installation was replaced.</param>
public sealed record InstallResult(
    InstallManifest Manifest,
    string ManifestPath,
    string ExecutablePath,
    bool WasUpgrade);

/// <summary>
/// Performs an installation: unpack, register, record.
/// </summary>
/// <remarks>
/// The order is the whole design. Files land first, then the manifest, then the registrations that
/// point at those files. If the process dies partway, what exists on disk is always a prefix of a
/// valid installation rather than a shortcut aiming at nothing — and every step that did complete
/// is already in the manifest, so the uninstaller can still clean up.
/// </remarks>
public sealed class InstallEngine(IPlatformIntegration platform)
{
    private readonly IPlatformIntegration _platform =
        platform ?? throw new ArgumentNullException(nameof(platform));

    /// <summary>
    /// Installs the payload in <paramref name="payloadStream"/> according to <paramref name="options"/>.
    /// </summary>
    /// <exception cref="InvalidOperationException">The options are unusable, or elevation is missing.</exception>
    /// <exception cref="InvalidDataException">The payload is corrupt.</exception>
    public async Task<InstallResult> InstallAsync(
        Stream payloadStream,
        InstallOptions options,
        IProgress<InstallProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(payloadStream);
        ArgumentNullException.ThrowIfNull(options);

        if (options.Validate() is { } error)
        {
            throw new InvalidOperationException(error);
        }

        if (options.Scope == InstallScope.AllUsers && !_platform.IsElevated)
        {
            throw new InvalidOperationException(
                "Installing for all users needs administrator rights. Restart the installer elevated, " +
                "or choose the per-user installation.");
        }

        var existing = _platform.FindInstalledProduct(options.Scope);
        var root = Path.GetFullPath(options.TargetDirectory);

        progress?.Report(new InstallProgress("Preparing", 0, 0));
        Directory.CreateDirectory(root);

        var index = await PayloadExtractor.ReadIndexAsync(payloadStream, cancellationToken).ConfigureAwait(false);
        payloadStream.Position = 0;

        var extraction = await PayloadExtractor
            .ExtractAsync(payloadStream, root, progress, cancellationToken)
            .ConfigureAwait(false);

        var executablePath = Path.Combine(root, ProductInfo.ExecutableRelativePath.Replace('\\', Path.DirectorySeparatorChar));

        // Everything after this point — both shortcuts, the file association, the Apps & features
        // icon and the uninstall command — is built from this one path. If the payload does not
        // actually contain it, every one of those points at nothing, and the installer would report
        // success while leaving an installation that cannot be launched or removed. Failing here
        // instead means a mismatched payload is caught the first time anybody runs setup.
        if (!File.Exists(executablePath))
        {
            throw new InvalidDataException(
                $"The payload does not contain '{ProductInfo.ExecutableRelativePath}'. It was built " +
                "from something that is not a Windows application image.");
        }

        progress?.Report(new InstallProgress("Registering with Windows", index.TotalBytes, index.TotalBytes));

        var shortcuts = CreateShortcuts(options, root, executablePath);
        var associations = RegisterAssociations(options, executablePath);
        var pathEntry = AddToPath(options, executablePath);

        var manifest = new InstallManifest
        {
            Version = index.Version,
            Scope = options.Scope,
            InstallRoot = root,
            InstalledUtc = DateTimeOffset.UtcNow,
            Files = extraction.Files,
            Directories = extraction.Directories,
            Shortcuts = shortcuts,
            FileAssociations = associations,
            PathEntry = pathEntry,
            UninstallEntryWritten = true,
        };

        // The manifest is written before the uninstall entry so that the entry, once visible in
        // Apps & features, always refers to an installation the uninstaller can actually read.
        var manifestPath = Path.Combine(root, ProductInfo.ManifestFileName);
        await File.WriteAllTextAsync(manifestPath, manifest.ToJson(), cancellationToken).ConfigureAwait(false);

        _platform.WriteUninstallEntry(options.Scope, new UninstallEntry(
            DisplayName: ProductInfo.DisplayName,
            DisplayVersion: index.Version,
            Publisher: ProductInfo.Publisher,
            InstallLocation: root,
            // Apps & features runs the application itself, which knows how to remove the
            // installation it is part of. Nothing extra is installed for this to work.
            UninstallCommand: $"\"{executablePath}\" {ProductInfo.UninstallSwitch}",
            QuietUninstallCommand: $"\"{executablePath}\" {ProductInfo.UninstallSwitch} /S",
            DisplayIcon: executablePath,
            HelpLink: ProductInfo.HelpLink,
            EstimatedSizeKilobytes: Math.Max(1, index.TotalBytes / 1024)));

        _platform.NotifyShellOfChanges();
        progress?.Report(new InstallProgress("Finished", index.TotalBytes, index.TotalBytes));

        return new InstallResult(manifest, manifestPath, executablePath, existing is not null);
    }

    private List<string> CreateShortcuts(InstallOptions options, string root, string executablePath)
    {
        var shortcuts = new List<string>(2);
        var workingDirectory = Path.GetDirectoryName(executablePath) ?? root;

        if (options.CreateStartMenuShortcut)
        {
            var path = Path.Combine(
                _platform.GetStartMenuDirectory(options.Scope),
                $"{ProductInfo.DisplayName}.lnk");
            _platform.CreateShortcut(new ShortcutDefinition(
                ShortcutPath: path,
                TargetPath: executablePath,
                WorkingDirectory: workingDirectory,
                Description: "Enterprise Markdown editor",
                IconPath: executablePath));
            shortcuts.Add(path);
        }

        if (options.CreateDesktopShortcut)
        {
            var path = Path.Combine(
                _platform.GetDesktopDirectory(options.Scope),
                $"{ProductInfo.DisplayName}.lnk");
            _platform.CreateShortcut(new ShortcutDefinition(
                ShortcutPath: path,
                TargetPath: executablePath,
                WorkingDirectory: workingDirectory,
                Description: "Enterprise Markdown editor",
                IconPath: executablePath));
            shortcuts.Add(path);
        }

        return shortcuts;
    }

    private List<string> RegisterAssociations(InstallOptions options, string executablePath)
    {
        if (!options.AssociateMarkdown)
        {
            return [];
        }

        var registered = new List<string>(ProductInfo.MarkdownExtensions.Count);
        foreach (var extension in ProductInfo.MarkdownExtensions)
        {
            _platform.RegisterFileAssociation(options.Scope, new FileAssociation(
                Extension: extension,
                ProgId: ProductInfo.ProgId,
                FriendlyTypeName: "Markdown Document",
                OpenCommand: $"\"{executablePath}\" \"%1\"",
                IconPath: executablePath));
            registered.Add(extension);
        }

        return registered;
    }

    private string? AddToPath(InstallOptions options, string executablePath)
    {
        if (!options.AddToPath)
        {
            return null;
        }

        var directory = Path.GetDirectoryName(executablePath);
        if (string.IsNullOrEmpty(directory))
        {
            return null;
        }

        _platform.AddToPath(options.Scope, directory);
        return directory;
    }
}
