using System.IO.Compression;
using System.Text;
using Quill.Setup.Core;

namespace Quill.Setup.Tests;

/// <summary>
/// Tests for packing and unpacking the payload.
/// </summary>
/// <remarks>
/// The hash check and the path check are the two things standing between a corrupt or hostile
/// archive and the user's file system, so each gets a test that actually produces the bad archive
/// rather than asserting on a mock.
/// </remarks>
public sealed class PayloadTests
{
    [Fact]
    public async Task Packing_records_every_file_with_its_hash()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();

        var index = await workspace.BuildPayloadAsync("2.0.0");

        Assert.Equal("2.0.0", index.Version);
        Assert.Equal(6, index.Entries.Count);
        Assert.All(index.Entries, entry => Assert.Equal(64, entry.Sha256.Length));
        Assert.Equal(index.Entries.Sum(entry => entry.Size), index.TotalBytes);

        // Archive paths are always '/'-separated, whatever platform packed them.
        Assert.All(index.Entries, entry => Assert.DoesNotContain('\\', entry.Path));
        Assert.Contains(index.Entries, entry => entry.Path == "lib/app/quill-app.jar");
    }

    [Fact]
    public async Task Extraction_restores_the_original_tree()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var target = Path.Combine(workspace.Root, "extracted");
        await using var payload = workspace.OpenPayload();
        var result = await PayloadExtractor.ExtractAsync(payload, target);

        Assert.Equal(6, result.Files.Count);
        Assert.Equal("MZ fake launcher", await File.ReadAllTextAsync(Path.Combine(target, "bin", "Quill.exe")));
        Assert.Equal("fake modules", await File.ReadAllTextAsync(Path.Combine(target, "lib", "runtime", "lib", "modules")));

        // Directories are recorded shallowest first so uninstall can walk them backwards.
        Assert.Equal(["bin", "lib", "lib/app", "lib/runtime", "lib/runtime/bin", "lib/runtime/lib"],
            result.Directories.Order(StringComparer.Ordinal));
        Assert.Equal("lib", result.Directories.First(directory => directory.StartsWith("lib", StringComparison.Ordinal)));
    }

    [Fact]
    public async Task Extraction_reports_progress_that_reaches_completion()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        // A synchronous sink rather than Progress<T>: Progress<T> posts through the captured
        // synchronization context, so what it reports is only observable after the test yields, and
        // asserting on it turns into a race with the scheduler.
        var reports = new CollectingProgress();
        var target = Path.Combine(workspace.Root, "extracted");
        await using var payload = workspace.OpenPayload();
        await PayloadExtractor.ExtractAsync(payload, target, reports);

        Assert.NotEmpty(reports.Reports);
        Assert.Equal(1.0, reports.Reports[^1].Fraction);
        Assert.Equal("Copying files", reports.Reports[^1].Stage);

        // Progress only ever moves forward; a bar that jumps backwards reads as a stall.
        var fractions = reports.Reports.Select(report => report.Fraction).ToList();
        Assert.Equal(fractions.Order(), fractions);
    }

    [Fact]
    public async Task A_tampered_file_fails_the_hash_check()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        // Rewrite one entry's content while leaving the index untouched, which is what a corrupt
        // download or a partially overwritten resource looks like.
        using (var archive = ZipFile.Open(workspace.PayloadArchive, ZipArchiveMode.Update))
        {
            var entry = archive.GetEntry("bin/Quill.exe")!;
            entry.Delete();
            var replacement = archive.CreateEntry("bin/Quill.exe");
            await using var stream = replacement.Open();
            await stream.WriteAsync(Encoding.UTF8.GetBytes("tampered"));
        }

        var target = Path.Combine(workspace.Root, "extracted");
        await using var payload = workspace.OpenPayload();

        var failure = await Assert.ThrowsAsync<InvalidDataException>(
            () => PayloadExtractor.ExtractAsync(payload, target));
        Assert.Contains("checksum", failure.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task An_entry_missing_from_the_archive_fails_the_extraction()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        using (var archive = ZipFile.Open(workspace.PayloadArchive, ZipArchiveMode.Update))
        {
            archive.GetEntry("README.txt")!.Delete();
        }

        var target = Path.Combine(workspace.Root, "extracted");
        await using var payload = workspace.OpenPayload();

        var failure = await Assert.ThrowsAsync<InvalidDataException>(
            () => PayloadExtractor.ExtractAsync(payload, target));
        Assert.Contains("missing", failure.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task An_entry_absent_from_the_index_fails_the_extraction()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        using (var archive = ZipFile.Open(workspace.PayloadArchive, ZipArchiveMode.Update))
        {
            var smuggled = archive.CreateEntry("bin/extra.dll");
            await using var stream = smuggled.Open();
            await stream.WriteAsync(Encoding.UTF8.GetBytes("smuggled"));
        }

        var target = Path.Combine(workspace.Root, "extracted");
        await using var payload = workspace.OpenPayload();

        await Assert.ThrowsAsync<InvalidDataException>(() => PayloadExtractor.ExtractAsync(payload, target));
    }

    [Theory]
    [InlineData("../escape.txt")]
    [InlineData("bin/../../escape.txt")]
    [InlineData("../../Windows/System32/evil.dll")]
    public async Task An_entry_escaping_the_install_root_is_refused(string entryName)
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        // Add the escaping entry to both the archive and its index, so the only thing that can stop
        // it is the path check itself.
        using (var archive = ZipFile.Open(workspace.PayloadArchive, ZipArchiveMode.Update))
        {
            var indexEntry = archive.GetEntry(PayloadIndex.EntryName)!;
            string json;
            await using (var read = indexEntry.Open())
            using (var reader = new StreamReader(read))
            {
                json = await reader.ReadToEndAsync();
            }

            var index = PayloadIndex.FromJson(json);
            var payloadBytes = Encoding.UTF8.GetBytes("evil");
            var hash = Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(payloadBytes));

            indexEntry.Delete();
            var replacementIndex = archive.CreateEntry(PayloadIndex.EntryName);
            await using (var write = replacementIndex.Open())
            await using (var writer = new StreamWriter(write))
            {
                var updated = index with
                {
                    Entries = [.. index.Entries, new PayloadEntry(entryName, payloadBytes.Length, hash)],
                };
                await writer.WriteAsync(updated.ToJson());
            }

            var evil = archive.CreateEntry(entryName);
            await using var evilStream = evil.Open();
            await evilStream.WriteAsync(payloadBytes);
        }

        var target = Path.Combine(workspace.Root, "extracted");
        await using var payload = workspace.OpenPayload();

        var failure = await Assert.ThrowsAsync<InvalidDataException>(
            () => PayloadExtractor.ExtractAsync(payload, target));
        Assert.Contains("escapes", failure.Message, StringComparison.OrdinalIgnoreCase);
        Assert.False(File.Exists(Path.Combine(workspace.Root, "escape.txt")));
    }

    [Fact]
    public async Task An_archive_without_an_index_is_refused()
    {
        using var workspace = new TemporaryWorkspace();
        var archivePath = Path.Combine(workspace.Root, "plain.zip");
        using (var archive = ZipFile.Open(archivePath, ZipArchiveMode.Create))
        {
            var entry = archive.CreateEntry("file.txt");
            await using var stream = entry.Open();
            await stream.WriteAsync(Encoding.UTF8.GetBytes("content"));
        }

        await using var payload = File.OpenRead(archivePath);
        var failure = await Assert.ThrowsAsync<InvalidDataException>(
            () => PayloadExtractor.ExtractAsync(payload, Path.Combine(workspace.Root, "out")));
        Assert.Contains("index", failure.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Extraction_honours_a_cancelled_token()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        using var cancellation = new CancellationTokenSource();
        await cancellation.CancelAsync();

        var target = Path.Combine(workspace.Root, "extracted");
        await using var payload = workspace.OpenPayload();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => PayloadExtractor.ExtractAsync(payload, target, progress: null, cancellation.Token));
    }

    [Fact]
    public async Task Installing_honours_a_cancelled_token()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        using var cancellation = new CancellationTokenSource();
        await cancellation.CancelAsync();

        await using var payload = workspace.OpenPayload();
        var engine = new InstallEngine(workspace.CreatePlatform());

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => engine.InstallAsync(payload, workspace.Options(), progress: null, cancellation.Token));
    }

    [Fact]
    public async Task Reading_the_index_does_not_extract_anything()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync("9.9.9");

        var target = Path.Combine(workspace.Root, "extracted");
        await using var payload = workspace.OpenPayload();
        var index = await PayloadExtractor.ReadIndexAsync(payload);

        Assert.Equal("9.9.9", index.Version);
        Assert.False(Directory.Exists(target));
    }

}

/// <summary>An <see cref="IProgress{T}"/> that records on the calling thread, with no marshalling.</summary>
internal sealed class CollectingProgress : IProgress<InstallProgress>
{
    private readonly List<InstallProgress> _reports = [];

    public IReadOnlyList<InstallProgress> Reports => _reports;

    public void Report(InstallProgress value) => _reports.Add(value);
}
