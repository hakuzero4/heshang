-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# LSPosed loads this class by the name in assets/xposed_init.
-keep class com.minedie.keepalive.hook.FrameworkHook { *; }

# The framework hook looks this probe up by name.
-keep class com.minedie.keepalive.xposed.ModuleProbe {
    public static boolean isActive();
}

-dontwarn de.robv.android.xposed.**
