# Security Policy

## Supported versions

Quill is at `1.0.x`. Security fixes go to the latest release; older ones are not patched.

| Version | Supported |
|---|---|
| 1.0.x | Yes |
| < 1.0 | No |

## Reporting a vulnerability

**Do not open a public issue.** Use GitHub's private reporting:

> **Security** → **Report a vulnerability** on <https://github.com/neramc/quill/security>

Include what you would want if you were fixing it: the version and platform, what an attacker can
do, and the smallest input or sequence of steps that demonstrates it. A document that triggers the
problem is worth more than a description of one.

You should get an acknowledgement within a few days. If a report is confirmed, the fix and an
advisory go out together, and you will be credited unless you would rather not be.

## Where the risk actually is

Quill parses untrusted documents, so that is where to look first.

**The engine handles untrusted input by design.** `quill-core` parses whatever is opened. Every FFI
entry point wraps its body in `catch_unwind`, so a panic returns an error status instead of
unwinding across the boundary into the JVM — which is undefined behaviour, not an exception.
Anything that gets a panic *past* that guard, or that produces a crash, a hang, or a read outside an
allocation, is a vulnerability. So is anything that makes the parser take time or memory
disproportionate to its input.

**The wire format is a trust boundary in one direction only.** QWIRE payloads travel from the engine
to the JVM. The decoder bounds-checks every read against the segment length precisely because those
bytes originate in native memory, where an over-read is not otherwise caught. A payload that gets
the decoder to read outside the segment is a vulnerability.

**Rendering does not execute anything.** The preview draws a parsed HTML tree with a fixed set of
element handlers. There is no script engine, no network fetch and no embedded browser, so a
`<script>` tag in a document is inert text. If you find a path by which document content causes
Quill to execute code, make a network request, or read a file the user did not open, that is a
vulnerability regardless of how it renders.

**The installer writes outside its own directory.** `installer-windows` creates shortcuts, registry
entries and file associations, and can be run elevated for an all-users install. Anything that lets
a non-elevated user influence what the elevated install writes is a vulnerability.

## Out of scope

- Anything requiring an attacker who already has code execution as the user.
- Crashes caused by a corrupt native library placed on the classpath by hand, or by
  `-Dquill.native.path` pointed at an attacker's file. Both require write access to the installation.
- The absence of a hardening measure, absent a concrete way to exploit its absence.
- Reports from automated scanners with no demonstrated impact.
