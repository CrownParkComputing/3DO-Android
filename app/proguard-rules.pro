# 3DO Opera — R8 / ProGuard rules
# (proguard-android-optimize.txt already keeps Activities, Views inflated from
#  XML, Parcelables, enums, etc. These rules cover this app's specifics.)

# --- JNI boundary -------------------------------------------------------------
# Native functions bind by name (Java_com_fourdo_android_MainActivity_*), so the
# declaring class and its native method names must survive shrinking/obfuscation.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.fourdo.android.MainActivity { *; }

# Custom Views constructed in code (and any inflated from XML).
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep line numbers for readable crash/ANR stack traces (deobfuscated via mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
