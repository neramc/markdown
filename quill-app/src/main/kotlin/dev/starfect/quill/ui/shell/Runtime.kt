package dev.starfect.quill.ui.shell

/**
 * Whether the application is running on the JetBrains Runtime.
 *
 * Jewel's `DecoratedWindow` draws the window's own title bar using JBR-only APIs and throws on any
 * other JVM. Quill's distribution bundles JBR, so the decorated window is what users see — but a
 * developer running `./gradlew run` on a stock JDK, or an administrator launching the jar with their
 * own runtime, should get a working editor rather than a crash. Detecting the runtime lets the
 * window decoration degrade while everything else stays identical.
 */
public fun isJetBrainsRuntime(): Boolean {
    val vendor = System.getProperty("java.vendor").orEmpty()
    val vendorVersion = System.getProperty("java.vendor.version").orEmpty()
    return vendor.contains("JetBrains", ignoreCase = true) ||
        vendorVersion.contains("JBR", ignoreCase = true) ||
        vendorVersion.contains("JetBrains", ignoreCase = true)
}
