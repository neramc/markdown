## What this changes

<!-- What the change does, and what it was for. If it fixes an issue, "Fixes #123". -->

## Why

<!--
The reasoning, not the mechanics — the diff already shows what moved. If this fixes something
subtle, describe the failure: the symptom is usually more useful to the next reader than the fix.
-->

## How it was verified

<!--
Which suites you ran, and what you added. If part of the change cannot be tested here — Windows
registry behaviour, macOS packaging — say so plainly rather than leaving it unexplained.
-->

- [ ] `cargo test --manifest-path quill-core/Cargo.toml`
- [ ] `cargo clippy --manifest-path quill-core/Cargo.toml --all-targets -- -D warnings`
- [ ] `./gradlew build`
- [ ] `dotnet test installer-windows/Quill.Setup.sln` (if `installer-windows/` changed)

## Notes for review

<!--
Anything worth flagging: a decision you were unsure about, an alternative you rejected, a follow-up
you deliberately left out of scope.

If this touches the FFI surface or the wire format, confirm both sides changed together and that a
round-trip test covers it.

If a UI change alters what is on screen, the offscreen renders in
quill-app/build/test-renders/ are the fastest way to show it.
-->
