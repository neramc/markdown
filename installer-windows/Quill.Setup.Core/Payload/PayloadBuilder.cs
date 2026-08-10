using System.IO.Compression;
using System.Security.Cryptography;

namespace Quill.Setup.Core;

/// <summary>
/// Packs an application image into the archive the installer embeds.
/// </summary>
/// <remarks>
/// Kept in the shared library rather than in a build script so the same code that writes the
/// archive is the code the tests read it back with. A packaging format described in two places
/// drifts, and the drift only shows up on a user's machine.
/// </remarks>
public static class PayloadBuilder
{
    /// <summary>
    /// Writes <paramref name="sourceDirectory"/> into a payload archive at
    /// <paramref name="destinationArchive"/>.
    /// </summary>
    /// <param name="sourceDirectory">The app image produced by the Gradle build.</param>
    /// <param name="destinationArchive">Archive to create, overwriting any existing file.</param>
    /// <param name="version">Version recorded in the index.</param>
    /// <param name="cancellationToken">Cancels a long pack.</param>
    /// <returns>The index that was written into the archive.</returns>
    /// <exception cref="DirectoryNotFoundException">The source directory does not exist.</exception>
    public static async Task<PayloadIndex> CreateAsync(
        string sourceDirectory,
        string destinationArchive,
        string version,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourceDirectory);
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationArchive);

        if (!Directory.Exists(sourceDirectory))
        {
            throw new DirectoryNotFoundException($"No such directory: {sourceDirectory}");
        }

        var root = Path.GetFullPath(sourceDirectory);
        var files = Directory.GetFiles(root, "*", SearchOption.AllDirectories);
        Array.Sort(files, StringComparer.Ordinal);

        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(destinationArchive))!);
        if (File.Exists(destinationArchive))
        {
            File.Delete(destinationArchive);
        }

        var entries = new List<PayloadEntry>(files.Length);
        long totalBytes = 0;

        await using (var stream = File.Create(destinationArchive))
        using (var archive = new ZipArchive(stream, ZipArchiveMode.Create))
        {
            foreach (var file in files)
            {
                cancellationToken.ThrowIfCancellationRequested();

                var relative = PayloadPath.Normalise(Path.GetRelativePath(root, file));
                var info = new FileInfo(file);

                var archiveEntry = archive.CreateEntry(relative, CompressionLevel.Optimal);
                await using (var source = File.OpenRead(file))
                await using (var target = archiveEntry.Open())
                {
                    await source.CopyToAsync(target, cancellationToken).ConfigureAwait(false);
                }

                entries.Add(new PayloadEntry(relative, info.Length, await HashFileAsync(file, cancellationToken)
                    .ConfigureAwait(false)));
                totalBytes += info.Length;
            }

            var index = new PayloadIndex
            {
                Version = version,
                TotalBytes = totalBytes,
                Entries = entries,
            };

            // The index goes in last and uncompressed: the extractor reads it before anything else,
            // and there is no point compressing a few kilobytes of JSON.
            var indexEntry = archive.CreateEntry(PayloadIndex.EntryName, CompressionLevel.NoCompression);
            await using (var indexStream = indexEntry.Open())
            await using (var writer = new StreamWriter(indexStream))
            {
                await writer.WriteAsync(index.ToJson().AsMemory(), cancellationToken).ConfigureAwait(false);
            }

            return index;
        }
    }

    private static async Task<string> HashFileAsync(string path, CancellationToken cancellationToken)
    {
        await using var stream = File.OpenRead(path);
        var hash = await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false);
        return Convert.ToHexStringLower(hash);
    }
}

/// <summary>Path helpers shared by the packer and the extractor.</summary>
internal static class PayloadPath
{
    /// <summary>Converts a platform path to the archive's '/'-separated form.</summary>
    internal static string Normalise(string relativePath) =>
        relativePath.Replace('\\', '/');

    /// <summary>
    /// Resolves an archive entry against the install root, refusing anything that escapes it.
    /// </summary>
    /// <remarks>
    /// This is the Zip Slip guard. An archive entry named <c>../../Windows/System32/…</c> would
    /// otherwise let a tampered payload write anywhere the installer can reach — and an all-users
    /// install reaches everywhere.
    /// </remarks>
    /// <exception cref="InvalidDataException">The entry points outside the install root.</exception>
    internal static string ResolveWithin(string root, string entryName)
    {
        if (string.IsNullOrWhiteSpace(entryName))
        {
            throw new InvalidDataException("The payload contains an entry with an empty name.");
        }

        if (Path.IsPathRooted(entryName) || entryName.Contains(':', StringComparison.Ordinal))
        {
            throw new InvalidDataException($"The payload entry '{entryName}' is not a relative path.");
        }

        var fullRoot = Path.GetFullPath(root);
        var candidate = Path.GetFullPath(Path.Combine(fullRoot, entryName.Replace('/', Path.DirectorySeparatorChar)));

        var rootWithSeparator = fullRoot.EndsWith(Path.DirectorySeparatorChar)
            ? fullRoot
            : fullRoot + Path.DirectorySeparatorChar;

        if (!candidate.StartsWith(rootWithSeparator, StringComparison.Ordinal))
        {
            throw new InvalidDataException($"The payload entry '{entryName}' escapes the installation folder.");
        }

        return candidate;
    }
}
