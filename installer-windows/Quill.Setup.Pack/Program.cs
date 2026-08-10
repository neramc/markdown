using Quill.Setup.Core;

namespace Quill.Setup.Pack;

/// <summary>
/// Packs an application image into the archive the installer embeds.
/// </summary>
/// <remarks>
/// A thin wrapper over <see cref="PayloadBuilder"/> rather than a shell script that zips a folder:
/// the archive carries a hash index the extractor verifies against, so the packer and the unpacker
/// have to be the same code. A zip produced by any other tool is rejected at install time, which is
/// the point.
/// </remarks>
internal static class Program
{
    private static async Task<int> Main(string[] args)
    {
        if (args.Length is < 2 or > 3)
        {
            await Console.Error.WriteLineAsync(
                "usage: quill-pack <app-image-directory> <output-archive> [version]");
            return 2;
        }

        var source = args[0];
        var destination = args[1];
        var version = args.Length == 3 ? args[2] : "0.0.0";

        try
        {
            var index = await PayloadBuilder.CreateAsync(source, destination, version);
            var megabytes = index.TotalBytes / (1024.0 * 1024.0);

            Console.WriteLine(
                $"packed {index.Entries.Count} files ({megabytes:F1} MiB) into {destination} " +
                $"as version {version}");
            return 0;
        }
        catch (Exception exception) when (exception is DirectoryNotFoundException or IOException
                                              or UnauthorizedAccessException or ArgumentException)
        {
            await Console.Error.WriteLineAsync(exception.Message);
            return 1;
        }
    }
}
