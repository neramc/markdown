# ProGuard configuration for the Quill release distribution.
#
# The release build shrinks and optimises but deliberately does not obfuscate: renaming buys nothing
# for a desktop application that ships no public API, and it breaks the reflective lookups Compose
# and Jewel perform on generated composable classes.

# -- Entry point --------------------------------------------------------------------------------

-keep public class dev.starfect.quill.MainKt {
    public static void main(java.lang.String[]);
}

# -- Panama / FFM -------------------------------------------------------------------------------
#
# The downcall layer resolves native symbols by name through SymbolLookup and builds every
# FunctionDescriptor by hand. Nothing reaches these members from bytecode ProGuard can see, so the
# whole bridge is kept: shrinking it away would leave the application starting with no engine.

-keep class dev.starfect.quill.bridge.internal.** { *; }
-keep class dev.starfect.quill.bridge.** { *; }

# ProGuard copies non-class jar entries through untouched, so the staged native library and the
# expui icon replacements need no rule of their own. (R8's -keepresources is not a ProGuard option
# and is rejected outright by the parser.)

# -- Compose ------------------------------------------------------------------------------------
#
# The Compose compiler plugin generates classes that are looked up reflectively at runtime, and the
# runtime itself reads annotations off composable functions.

-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.text.font.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable *;
}
-keepclasseswithmembers class * {
    public static void main(java.lang.String[]);
}

# Skiko loads its own native library and dispatches through JNI; renaming or removing any of it
# breaks rendering with an UnsatisfiedLinkError at the first frame.
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }

# -- Jewel --------------------------------------------------------------------------------------
#
# Jewel resolves icons, themes and window decoration by resource path and reflection over the
# JetBrains Runtime's window APIs.

-keep class org.jetbrains.jewel.** { *; }

# The JetBrains Runtime API. Every service in jbr-api is resolved at runtime through generated
# JBR$Xxx__Holder classes that nothing references statically, so shrinking removes them — 45 of the
# jar's 65 classes in the first release build — and com.jetbrains.JBR then reports that the API is
# unavailable. The window has a working title bar in the debug distribution and throws
# "DecoratedWindow can only be used on JetBrainsRuntime(JBR)" in the release one, which is as
# confusing a bug as this build can produce. Keep the whole API.
-keep class com.jetbrains.** { *; }
-dontwarn com.jetbrains.**

# -- Kotlin -------------------------------------------------------------------------------------

-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory

# -- Warnings we accept -------------------------------------------------------------------------
#
# Jewel's Markdown module bundles a commonmark-based processor that Quill never calls: the engine
# produces the block IR itself and IrToJewel maps it straight onto Jewel's model. Some of that
# processor's signatures reference commonmark extension types that are not on our classpath, and
# ProGuard treats unresolved library class members as fatal rather than as dead code.
-dontwarn org.commonmark.**

# The same shape of problem, from four more directions.
#
#   org.jetbrains.annotations  compile-time only (@ApiStatus.Internal and friends); it is not on the
#                              runtime classpath by design, and nothing reads it at runtime.
#   ...markdown...github.tables  Jewel's table extension, which Quill does not depend on because it
#                              renders tables itself -- see PreviewPane.MarkdownTable.
#   kotlin.concurrent.atomics  a newer stdlib surface referenced by coroutines but absent from the
#                              stdlib version resolved here.
#   org.jsoup                  an optional HTML-cleaning dependency of the bundled commonmark.
-dontwarn org.jetbrains.annotations.**
-dontwarn org.jetbrains.jewel.markdown.extensions.github.tables.**
-dontwarn kotlin.concurrent.atomics.**
-dontwarn org.jsoup.**

# @RequiresOptIn and its Level enum have SOURCE retention, so the classes genuinely do not exist in
# any jar. Jewel's experimental annotations reference them from their own metadata. The trailing **
# is needed for the nested Level enum: ProGuard reports nested classes with a dot, so a '$Level'
# pattern never matches what it printed.
-dontwarn kotlin.RequiresOptIn**

# MethodHandle.invokeExact is signature-polymorphic: the compiler emits a call whose descriptor is
# taken from the call site, so no method with that descriptor exists in java.lang.invoke for
# ProGuard to resolve. Every warning here is one of the bridge's FFM downcalls and is expected.
#
# This is safe only because the release build does not obfuscate. Renaming would rewrite these
# descriptors, and a polymorphic call whose descriptor no longer matches the linked native function
# fails at runtime with a WrongMethodTypeException rather than at build time.
-dontwarn java.lang.invoke.MethodHandle

# Coroutines, Skiko and the AndroidX collections carry optional integrations (Android, debug agents,
# alternative schedulers) that are absent from a desktop JVM classpath by design.
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.atomicfu.**
-dontwarn org.jetbrains.skiko.**
-dontwarn androidx.**
-dontwarn org.slf4j.**
-dontwarn java.lang.instrument.**
-dontwarn sun.misc.**

# -- Diagnostics ---------------------------------------------------------------------------------
#
# Line numbers survive so a stack trace from a release build still points at real source lines.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
