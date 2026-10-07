# DAT ships its own consumer keep rules (com.meta.wearable.**).

# kotlinx.serialization DTOs
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.basira.app.data.**.dto.** { *; }
-keep,includedescriptorclasses class com.basira.app.data.**.dto.**$$serializer { *; }

# Strip verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
