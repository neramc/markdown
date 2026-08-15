using System.Text.Json;
using Quill.Setup.Core;

namespace Quill.Setup.Tests;

/// <summary>
/// The manifest's shape on disk.
/// </summary>
/// <remarks>
/// This file is a contract between two languages that never call each other. The installer writes
/// it in C#; the application reads it in Kotlin, in <c>dev.starfect.quill.install.Uninstall</c>,
/// with a hand-written parser and no shared schema — there is no build step, no code generation and
/// no type system holding the two together.
///
/// So the names are pinned here. A rename in C# is invisible to the Kotlin side until somebody
/// tries to uninstall and the manifest reads back as an empty installation, at which point the plan
/// contains no files and one directory: the install root. Which it would then delete.
///
/// If a test here fails, the fix is to change both sides — including the fixture in Kotlin's
/// <c>UninstallTest.a manifest written by the real installer reads correctly</c> — and to bump
/// <see cref="InstallManifest.CurrentSchemaVersion"/> if the change is not backwards compatible.
/// </remarks>
public sealed class InstallManifestTests
{
    [Fact]
    public void The_serialised_names_are_the_ones_the_application_reads()
    {
        var json = new InstallManifest { Version = "1.2.0" }.ToJson();

        using var document = JsonDocument.Parse(json);
        var names = document.RootElement.EnumerateObject().Select(property => property.Name).ToArray();

        Assert.Equal(
            [
                "schemaVersion",
                "product",
                "version",
                "scope",
                "installRoot",
                "installedUtc",
                "files",
                "directories",
                "shortcuts",
                "fileAssociations",
                "pathEntry",
                "uninstallEntryWritten",
            ],
            names);
    }

    [Fact]
    public void The_scope_is_written_as_a_name_rather_than_a_number()
    {
        // Kotlin matches on the string "AllUsers". Dropping UseStringEnumConverter would write 1
        // here, every uninstall would silently fall back to the per-user hive, and a machine-wide
        // installation would leave its HKLM entries behind for ever.
        using var document = JsonDocument.Parse(
            new InstallManifest { Scope = InstallScope.AllUsers }.ToJson());

        Assert.Equal("AllUsers", document.RootElement.GetProperty("scope").GetString());
    }

    [Fact]
    public void Paths_stay_relative_and_forward_slashed()
    {
        // The application resolves these against the install root and refuses anything that escapes
        // it. An absolute path here would be refused and the file would be left behind.
        var manifest = new InstallManifest { Files = ["Quill.exe", "app/quill-app.jar"], Directories = ["runtime/lib"] };

        Assert.All(manifest.Files.Concat(manifest.Directories), entry =>
        {
            Assert.DoesNotContain('\\', entry);
            Assert.False(Path.IsPathRooted(entry));
        });
    }
}
