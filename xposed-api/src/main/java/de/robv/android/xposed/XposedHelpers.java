package de.robv.android.xposed;

public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static int getIntField(Object obj, String fieldName) {
        return 0;
    }

    public static void setIntField(Object obj, String fieldName, int value) {
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static int getStaticIntField(Class<?> clazz, String fieldName) {
        return 0;
    }
}
