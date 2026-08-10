using System.Text.Json;
using System.Text.Json.Serialization;

namespace Quill.Setup.Core;

/// <summary>
/// The record of what an installation put on the machine.
/// </summary>
/// <remarks>
/// This is the contract between the installer and the uninstaller, and it is why the uninstaller
/// needs no knowledge of the payload. Removal is a replay of this list in reverse rather than a
/// guess at what "a Quill installation" looks like, so an uninstall never deletes a file the
/// installer did not write — which is the failure mode that makes an uninstaller dangerous.
/// </remarks>
public sealed record InstallManifest
{
    /// <summary>Bumped when the shape below changes incompatibly.</summary>
    public const int CurrentSchemaVersion = 1;

    [JsonPropertyName("schemaVersion")]
    public int SchemaVersion { get; init; } = CurrentSchemaVersion;

    [JsonPropertyName("product")]
    public string Product { get; init; } = ProductInfo.DisplayName;

    [JsonPropertyName("version")]
    public string Version { get; init; } = "0.0.0";

    [JsonPropertyName("scope")]
    public InstallScope Scope { get; init; } = InstallScope.CurrentUser;

    [JsonPropertyName("installRoot")]
    public string InstallRoot { get; init; } = string.Empty;

    [JsonPropertyName("installedUtc")]
    public DateTimeOffset InstalledUtc { get; init; } = DateTimeOffset.UtcNow;

    /// <summary>Files written, relative to <see cref="InstallRoot"/> and separated by '/'.</summary>
    [JsonPropertyName("files")]
    public IReadOnlyList<string> Files { get; init; } = [];

    /// <summary>Directories created, relative to <see cref="InstallRoot"/>, shallowest first.</summary>
    [JsonPropertyName("directories")]
    public IReadOnlyList<string> Directories { get; init; } = [];

    /// <summary>Absolute paths of shortcuts created.</summary>
    [JsonPropertyName("shortcuts")]
    public IReadOnlyList<string> Shortcuts { get; init; } = [];

    /// <summary>Extensions associated with <see cref="ProductInfo.ProgId"/>.</summary>
    [JsonPropertyName("fileAssociations")]
    public IReadOnlyList<string> FileAssociations { get; init; } = [];

    /// <summary>The directory added to PATH, when the user asked for it.</summary>
    [JsonPropertyName("pathEntry")]
    public string? PathEntry { get; init; }

    /// <summary>Whether an Uninstall registry entry was written.</summary>
    [JsonPropertyName("uninstallEntryWritten")]
    public bool UninstallEntryWritten { get; init; }

    /// <summary>Serialises to the on-disk form, indented so a human can audit it.</summary>
    public string ToJson() => JsonSerializer.Serialize(this, SetupJsonContext.Default.InstallManifest);

    /// <summary>
    /// Parses a manifest.
    /// </summary>
    /// <exception cref="InvalidDataException">
    /// The file is not a manifest, or was written by a newer, incompatible installer. Refusing a
    /// future schema is deliberate: silently misreading it would delete the wrong files.
    /// </exception>
    public static InstallManifest FromJson(string json)
    {
        InstallManifest? manifest;
        try
        {
            manifest = JsonSerializer.Deserialize(json, SetupJsonContext.Default.InstallManifest);
        }
        catch (JsonException exception)
        {
            throw new InvalidDataException("The installation manifest is not valid JSON.", exception);
        }

        if (manifest is null)
        {
            throw new InvalidDataException("The installation manifest is empty.");
        }

        if (manifest.SchemaVersion > CurrentSchemaVersion)
        {
            throw new InvalidDataException(
                $"The installation manifest uses schema version {manifest.SchemaVersion}, but this " +
                $"uninstaller understands only up to {CurrentSchemaVersion}. Use the uninstaller " +
                "that shipped with the installed version.");
        }

        return manifest;
    }
}

/// <summary>
/// Source-generated JSON contracts.
/// </summary>
/// <remarks>
/// Source generation rather than reflection, because both executables are published as single-file
/// self-contained binaries where the reflection-based serialiser is the first thing to break.
/// </remarks>
[JsonSourceGenerationOptions(WriteIndented = true, UseStringEnumConverter = true)]
[JsonSerializable(typeof(InstallManifest))]
[JsonSerializable(typeof(PayloadIndex))]
public sealed partial class SetupJsonContext : JsonSerializerContext;
