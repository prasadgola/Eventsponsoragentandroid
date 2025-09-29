# Add project specific ProGuard rules here.

# Keep source file and line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ===== RETROFIT =====
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ===== OKHTTP =====
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ===== GSON =====
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Gson uses generic type information stored in a class file when working with fields.
-keepattributes Signature

# Gson specific classes
-keep class sun.misc.Unsafe { *; }

# ===== YOUR DATA CLASSES (CRITICAL!) =====
# Keep ALL data classes in your app
-keep class com.example.eventsponsorassistant.MessagePart { *; }
-keep class com.example.eventsponsorassistant.NewMessage { *; }
-keep class com.example.eventsponsorassistant.ApiRequest { *; }
-keep class com.example.eventsponsorassistant.ApiContent { *; }
-keep class com.example.eventsponsorassistant.ApiEvent { *; }

# Keep your Retrofit service interface
-keep interface com.example.eventsponsorassistant.ApiService { *; }

# Keep your RetrofitClient
-keep class com.example.eventsponsorassistant.RetrofitClient { *; }

# ===== KOTLIN =====
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Keep data class generated methods
-keepclassmembers class * {
    public <init>(...);
}

# ===== COROUTINES =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.** {
    volatile <fields>;
}

# ===== BROADER SAFETY NET =====
# If a class is kept, keep all its members
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep all classes that are used with Gson
-keep class * {
    @com.google.gson.annotations.SerializedName <fields>;
}