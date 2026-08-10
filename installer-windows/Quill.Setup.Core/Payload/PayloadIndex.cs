using System.Text.Json;
using System.Text.Json.Serialization;

namespace Quill.Setup.Core;

/// <summary>One file in the payload, with the hash the extractor checks it against.</summary>
/// <param name="Path">Relative path using '/' separators, as stored in the archive.</param>
/// <param name="Size">Uncompressed length in bytes.</param>
/// <param name="Sha256">Lowercase hex SHA-256 of the uncompressed content.</param>
public sealed record PayloadEntry(
    [property: JsonPropertyName("path")] string Path,
    [property: JsonPropertyName("size")] long Size,
    [property: JsonPropertyName("sha256")] string Sha256);

/// <summary>
/// The manifest carried inside the payload archive.
/// </summary>
/// <remarks>
/// The index exists so extraction can fail loudly rather than quietly. A truncated download or a
/// corrupted embedded resource otherwise produces an installation that looks complete and crashes
/// on first launch, and the user has no way to tell those two apart.
/// </remarks>
public sealed record PayloadIndex
{
    /// <summary>Archive entry name reserved for the index itself.</summary>
    public const string EntryName = ".quill-payload-index.json";

    [JsonPropertyName("product")]
    public string Product { get; init; } = ProductInfo.DisplayName;

    [JsonPropertyName("version")]
    public string Version { get; init; } = "0.0.0";

    [JsonPropertyName("totalBytes")]
    public long TotalBytes { get; init; }

    [JsonPropertyName("entries")]
    public IReadOnlyList<PayloadEntry> Entries { get; init; } = [];

    public string ToJson() => JsonSerializer.Serialize(this, SetupJsonContext.Default.PayloadIndex);

    /// <exception cref="InvalidDataException">The index is missing or malformed.</exception>
    public static PayloadIndex FromJson(string json)
    {
        PayloadIndex? index;
        try
        {
            index = JsonSerializer.Deserialize(json, SetupJsonContext.Default.PayloadIndex);
        }
        catch (JsonException exception)
        {
            throw new InvalidDataException("The payload index is not valid JSON.", exception);
        }

        return index ?? throw new InvalidDataException("The payload index is empty.");
    }
}
