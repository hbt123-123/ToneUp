# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# kotlinx-serialization: keep serializers
-keepattributes *Annotation*, InnerClasses
# Retrofit resolves response types via generic signatures; required under R8
-keepattributes Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.toneup.app.**$$serializer { *; }
-keepclasseswithmembers class com.toneup.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WebView KaTeX bridge
-keepclassmembers class com.toneup.app.ui.components.formula.** { public *; }
