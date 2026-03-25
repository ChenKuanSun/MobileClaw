# MobileClaw ProGuard Rules

# Kotlin Metadata (R8 compat with Kotlin 2.2+)
-dontwarn kotlin.Metadata

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ai.affiora.mobileclaw.**$$serializer { *; }
-keepclassmembers class ai.affiora.mobileclaw.** {
    *** Companion;
}
-keepclasseswithmembers class ai.affiora.mobileclaw.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Anthropic SDK + its dependencies (jsonschema-generator uses JVM reflection not on Android)
-keep class com.anthropic.** { *; }
-dontwarn com.anthropic.**
-dontwarn com.github.victools.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType

# AppAuth
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# LiteRT-LM (on-device inference)
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Keep data classes used by serialization
-keep class ai.affiora.mobileclaw.data.model.** { *; }
