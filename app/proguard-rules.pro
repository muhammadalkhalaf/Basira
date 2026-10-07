# DAT ships its own consumer keep rules (com.meta.wearable.**).

# Crashlytics needs these to turn a release stack trace back into a file and a line, which is what
# makes a non-fatal report worth reading. The Crashlytics Gradle plugin uploads the mapping file, so
# the original file names are restored in the dashboard without shipping them in the APK.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep public class * extends java.lang.Exception

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
