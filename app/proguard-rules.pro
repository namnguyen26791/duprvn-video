# ============================================
# Retrofit
# ============================================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ============================================
# Gson
# ============================================
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ============================================
# OkHttp
# ============================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ============================================
# App data models (Gson serialization)
# ============================================
-keep class asia.pickbase.video.data.** { *; }

# ============================================
# Kotlin
# ============================================
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============================================
# Coroutines
# ============================================
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
