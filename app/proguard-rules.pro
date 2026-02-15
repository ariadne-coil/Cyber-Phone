# Safety net: disable obfuscation to avoid JNI/reflection breakage in native-heavy dependencies
# (JNA, UniFFI bindings, etc.). We still keep shrinking enabled for APK size.
-dontobfuscate
# R8 has produced VerifyErrors on some devices with aggressive optimizations enabled.
# Keep shrinking, but disable optimizations to prioritize runtime correctness.
-dontoptimize

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Gson
-keepattributes Signature
-keep class org.fossify.commons.models.SimpleContact { *; }
-keep class org.fossify.messages.models.Attachment { *; }
-keep class org.fossify.messages.models.MessageAttachment { *; }

# Msgpack uses reflection to load MessageBufferU; keep it for release builds.
-keep class org.msgpack.core.buffer.MessageBufferU { *; }

# JNA (used by ldk-node-android). JNA's native layer looks up specific field/method names via JNI,
# so we must prevent obfuscation of JNA classes and members.
-keep class com.sun.jna.** { *; }
-keep class com.sun.jna.ptr.** { *; }

# ldk-node-android's UniFFI/JNA bindings also rely on reflection and native lookups for structure
# layouts and callbacks, so we must not shrink/obfuscate them.
-keep class org.lightningdevkit.ldknode.** { *; }
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window

-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn sun.nio.ch.DirectBuffer

# WebView JS bridges rely on exact method names looked up from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
