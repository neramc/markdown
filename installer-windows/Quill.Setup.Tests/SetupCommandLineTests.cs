using Quill.Setup.Core;

namespace Quill.Setup.Tests;

/// <summary>
/// Tests for the command line the installer, the elevated relaunch and Apps &amp; features all speak.
/// </summary>
/// <remarks>
/// This parser is the interface between two processes that cannot ask each other questions: the
/// unelevated wizard hands its state to an elevated copy of itself entirely through these switches.
/// A silently dropped flag there means the user ticks a box and it quietly does not happen.
/// </remarks>
public sealed class SetupCommandLineTests
{
    [Fact]
    public void No_arguments_means_an_interactive_per_user_install()
    {
        var command = SetupCommandLine.Parse([]);

        Assert.False(command.Silent);
        Assert.False(command.AllUsers);
        Assert.Null(command.TargetDirectory);
        Assert.False(command.HasComponentFlags);
    }

    [Theory]
    [InlineData("/S")]
    [InlineData("/s")]
    [InlineData("--silent")]
    public void The_silent_switch_is_recognised_in_the_forms_windows_uses(string argument)
    {
        Assert.True(SetupCommandLine.Parse([argument]).Silent);
    }

    [Fact]
    public void The_target_directory_is_read_in_both_spellings()
    {
        Assert.Equal(@"C:\Apps\Quill", SetupCommandLine.Parse(["--target", @"C:\Apps\Quill"]).TargetDirectory);
        Assert.Equal(@"C:\Apps\Quill", SetupCommandLine.Parse([@"--target=C:\Apps\Quill"]).TargetDirectory);
    }

    [Fact]
    public void A_trailing_target_switch_with_no_value_is_ignored_rather_than_crashing()
    {
        Assert.Null(SetupCommandLine.Parse(["--target"]).TargetDirectory);
    }

    [Fact]
    public void Every_component_switch_survives_the_round_trip()
    {
        using var workspace = new TemporaryWorkspace();
        var platform = workspace.CreatePlatform(isElevated: true);

        var command = SetupCommandLine.Parse([
            "--all-users", "--target", workspace.InstallRoot,
            "--start-menu", "--desktop", "--associate", "--add-to-path",
        ]);
        var options = command.ToInstallOptions(platform);

        Assert.Equal(InstallScope.AllUsers, options.Scope);
        Assert.Equal(workspace.InstallRoot, options.TargetDirectory);
        Assert.True(options.CreateStartMenuShortcut);
        Assert.True(options.CreateDesktopShortcut);
        Assert.True(options.AssociateMarkdown);
        Assert.True(options.AddToPath);
    }

    [Fact]
    public void An_unticked_box_stays_unticked_across_the_elevation_boundary()
    {
        using var workspace = new TemporaryWorkspace();
        var platform = workspace.CreatePlatform(isElevated: true);

        // The wizard only passes the switches the user ticked. Because at least one was passed, the
        // absent ones mean "no" rather than "unspecified" — that distinction is the whole reason
        // HasComponentFlags exists.
        var options = SetupCommandLine
            .Parse(["--all-users", "--target", workspace.InstallRoot, "--start-menu"])
            .ToInstallOptions(platform);

        Assert.True(options.CreateStartMenuShortcut);
        Assert.False(options.CreateDesktopShortcut);
        Assert.False(options.AssociateMarkdown);
        Assert.False(options.AddToPath);
    }

    [Fact]
    public void A_bare_silent_install_uses_the_normal_defaults()
    {
        using var workspace = new TemporaryWorkspace();
        var platform = workspace.CreatePlatform();

        var options = SetupCommandLine.Parse(["/S"]).ToInstallOptions(platform);

        Assert.Equal(InstallScope.CurrentUser, options.Scope);
        Assert.Equal(platform.GetDefaultInstallRoot(InstallScope.CurrentUser), options.TargetDirectory);
        Assert.True(options.CreateStartMenuShortcut);
        Assert.True(options.AssociateMarkdown);
        Assert.False(options.CreateDesktopShortcut);
        Assert.False(options.AddToPath);
    }

    [Fact]
    public void The_last_scope_switch_wins()
    {
        Assert.False(SetupCommandLine.Parse(["--all-users", "--current-user"]).AllUsers);
        Assert.True(SetupCommandLine.Parse(["--current-user", "--all-users"]).AllUsers);
    }

    [Fact]
    public void Unknown_switches_are_ignored()
    {
        var command = SetupCommandLine.Parse(["--who-knows", "-x", "/S"]);
        Assert.True(command.Silent);
    }

    [Fact]
    public void The_payload_path_is_read_in_both_spellings()
    {
        Assert.Equal("/tmp/p.zip", PayloadSource.TryGetFilePath([PayloadSource.FileSwitch, "/tmp/p.zip"]));
        Assert.Equal("/tmp/p.zip", PayloadSource.TryGetFilePath([$"{PayloadSource.FileSwitch}=/tmp/p.zip"]));
        Assert.Null(PayloadSource.TryGetFilePath(["--other", "/tmp/p.zip"]));
    }

    [Fact]
    public async Task An_external_payload_file_is_opened_when_given()
    {
        using var workspace = new TemporaryWorkspace();
        workspace.AddDefaultAppImage();
        await workspace.BuildPayloadAsync();

        var assembly = typeof(SetupCommandLineTests).Assembly;
        var (stream, origin) = PayloadSource.Open(assembly, [PayloadSource.FileSwitch, workspace.PayloadArchive]);

        await using (stream)
        {
            Assert.NotNull(stream);
            Assert.Equal(PayloadOrigin.ExternalFile, origin);
        }
    }

    [Fact]
    public void An_assembly_with_no_embedded_payload_reports_none()
    {
        // The test assembly embeds nothing, which is exactly the "built from a plain checkout" case
        // the wizard has to survive.
        var (stream, origin) = PayloadSource.Open(typeof(SetupCommandLineTests).Assembly, []);

        Assert.Null(stream);
        Assert.Equal(PayloadOrigin.None, origin);
    }
}
