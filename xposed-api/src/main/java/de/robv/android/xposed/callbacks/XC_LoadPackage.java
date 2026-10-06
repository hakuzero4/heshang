package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XCallback;

public abstract class XC_LoadPackage extends XCallback implements IXposedHookLoadPackage {
    public static final class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
