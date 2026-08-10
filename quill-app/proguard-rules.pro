# ProGuard configuration for the Quill release distribution.
#
# The release build shrinks and optimises but deliberately does not obfuscate: renaming buys nothing
# for a desktop application that ships no public API, and it breaks the reflective lookups Compose
# and Jewel perform on generated composable classes.

# -- Entry point --------------------------------------------------------------------------------

-keep public class com.neramc.quill.MainKt {
    public static void main(java.lang.String[]);
}

# -- Panama / FFM -------------------------------------------------------------------------------
#
# The downcall layer resolves native symbols by name through SymbolLookup and builds every
# FunctionDescriptor by hand. Nothing reaches these members from bytecode ProGuard can see, so the
# whole bridge is kept: shrinking it away would leave the application starting with no engine.

-keep class com.neramc.quill.bridge.internal.** { *; }
-keep class com.neramc.quill.bridge.** { *; }

# The native library is extracted from the jar at startup by resource name.
-keepresources native/**
-keepresources **.so
-keepresources **.dll
-keepresources **.dylib

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
-keepresources expui/**
-keepresources com/intellij/**

# -- Kotlin -------------------------------------------------------------------------------------

-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory
-keepresources META-INF/services/**

# -- Warnings we accept -------------------------------------------------------------------------
#
# Jewel's Markdown module bundles a commonmark-based processor that Quill never calls: the engine
# produces the block IR itself and IrToJewel maps it straight onto Jewel's model. Some of that
# processor's signatures reference commonmark extension types that are not on our classpath, and
# ProGuard treats unresolved library class members as fatal rather than as dead code.
-dontwarn org.commonmark.**

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
