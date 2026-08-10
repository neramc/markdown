using System.IO.Compression;
using System.Security.Cryptography;

namespace Quill.Setup.Core;

/// <summary>Progress of a long-running install or uninstall step.</summary>
/// <param name="Stage">What is happening, phrased for a progress label.</param>
/// <param name="Completed">Units done so far.</param>
/// <param name="Total">Total units, or 0 when unknown.</param>
public readonly record struct InstallProgress(string Stage, long Completed, long Total)
{
    /// <summary>Fraction in 0..1, or 0 when the total is unknown.</summary>
    public double Fraction => Total <= 0 ? 0 : Math.Clamp((double)Completed / Total, 0, 1);
}

/// <summary>What an extraction produced, for the manifest.</summary>
/// <param name="Files">Relative file paths written, '/'-separated.</param>
/// <param name="Directories">Relative directories created, shallowest first.</param>
public sealed record ExtractionResult(IReadOnlyList<string> Files, IReadOnlyList<string> Directories);

/// <summary>Unpacks a payload archive, verifying every file against the index it carries.</summary>
public static class PayloadExtractor
{
    /// <summary>Reads the index without extracting, so the wizard can show a size before committing.</summary>
    /// <exception cref="InvalidDataException">The archive carries no index.</exception>
    public static async Task<PayloadIndex> ReadIndexAsync(
        Stream archiveStream,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(archiveStream);

        using var archive = new ZipArchive(archiveStream, ZipArchiveMode.Read, leaveOpen: true);
        return await ReadIndexAsync(archive, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Extracts every payload entry into <paramref name="targetRoot"/>.
    /// </summary>
    /// <remarks>
    /// Each file's SHA-256 is recomputed as it lands and compared with the index. Verifying after
    /// the write rather than before means the check covers the disk too, not just the archive: a
    /// failing drive that silently drops a sector fails the install here instead of at first launch.
    /// </remarks>
    /// <exception cref="InvalidDataException">
    /// An entry is missing from the index, escapes the install root, or does not match its hash.
    /// </exception>
    public static async Task<ExtractionResult> ExtractAsync(
        Stream archiveStream,
        string targetRoot,
        IProgress<InstallProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(archiveStream);
        ArgumentException.ThrowIfNullOrWhiteSpace(targetRoot);

        using var archive = new ZipArchive(archiveStream, ZipArchiveMode.Read, leaveOpen: true);
        var index = await ReadIndexAsync(archive, cancellationToken).ConfigureAwait(false);
        var expected = index.Entries.ToDictionary(entry => entry.Path, StringComparer.Ordinal);

        Directory.CreateDirectory(targetRoot);

        var files = new List<string>(expected.Count);
        var directories = new List<string>();
        var seenDirectories = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        long written = 0;

        foreach (var archiveEntry in archive.Entries)
        {
            cancellationToken.ThrowIfCancellationRequested();

            if (string.Equals(archiveEntry.FullName, PayloadIndex.EntryName, StringComparison.Ordinal))
            {
                continue;
            }

            // Directory entries are zero-length names ending in '/'; the directories are created
            // from the file paths anyway, so they carry no information worth trusting.
            if (archiveEntry.FullName.EndsWith('/'))
            {
                continue;
            }

            if (!expected.TryGetValue(archiveEntry.FullName, out var entry))
            {
                throw new InvalidDataException(
                    $"The payload contains '{archiveEntry.FullName}', which is not listed in its index.");
            }

            var destination = PayloadPath.ResolveWithin(targetRoot, archiveEntry.FullName);
            RecordDirectories(targetRoot, archiveEntry.FullName, directories, seenDirectories);
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);

            progress?.Report(new InstallProgress($"Copying {archiveEntry.FullName}", written, index.TotalBytes));

            var actualHash = await CopyAndHashAsync(archiveEntry, destination, cancellationToken)
                .ConfigureAwait(false);

            if (!string.Equals(actualHash, entry.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException(
                    $"'{archiveEntry.FullName}' does not match its recorded checksum. The installer " +
                    "package is corrupt or incomplete.");
            }

            files.Add(archiveEntry.FullName);
            written += entry.Size;
        }

        var missing = expected.Keys.Except(files, StringComparer.Ordinal).ToList();
        if (missing.Count > 0)
        {
            throw new InvalidDataException(
                $"The payload is missing {missing.Count} file(s) its index promised, starting with " +
                $"'{missing[0]}'.");
        }

        progress?.Report(new InstallProgress("Copying files", index.TotalBytes, index.TotalBytes));
        return new ExtractionResult(files, directories);
    }

    private static async Task<PayloadIndex> ReadIndexAsync(ZipArchive archive, CancellationToken cancellationToken)
    {
        var indexEntry = archive.GetEntry(PayloadIndex.EntryName)
            ?? throw new InvalidDataException(
                "The payload archive has no index. It was not produced by this installer's packer.");

        await using var stream = indexEntry.Open();
        using var reader = new StreamReader(stream);
        var json = await reader.ReadToEndAsync(cancellationToken).ConfigureAwait(false);
        return PayloadIndex.FromJson(json);
    }

    private static async Task<string> CopyAndHashAsync(
        ZipArchiveEntry entry,
        string destination,
        CancellationToken cancellationToken)
    {
        await using (var source = entry.Open())
        await using (var target = File.Create(destination))
        {
            await source.CopyToAsync(target, cancellationToken).ConfigureAwait(false);
        }

        await using var written = File.OpenRead(destination);
        var hash = await SHA256.HashDataAsync(written, cancellationToken).ConfigureAwait(false);
        return Convert.ToHexStringLower(hash);
    }

    /// <summary>Records each ancestor directory of an entry, shallowest first and without repeats.</summary>
    private static void RecordDirectories(
        string targetRoot,
        string entryName,
        List<string> directories,
        HashSet<string> seen)
    {
        var separator = entryName.LastIndexOf('/');
        if (separator <= 0)
        {
            return;
        }

        var segments = entryName[..separator].Split('/', StringSplitOptions.RemoveEmptyEntries);
        var current = string.Empty;
        foreach (var segment in segments)
        {
            current = current.Length == 0 ? segment : $"{current}/{segment}";

            // Guard each level too: a path that escapes the root does so at some ancestor.
            _ = PayloadPath.ResolveWithin(targetRoot, current);

            if (seen.Add(current))
            {
                directories.Add(current);
            }
        }
    }
}
