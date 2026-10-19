# BufferSynk proguard rules (minify currently disabled; rules kept for future).
-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.serialization.**
-keep,includedescriptorclasses class com.buffersynk.app.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.buffersynk.app.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.buffersynk.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.journeyapps.barcodescanner.** { *; }
