package de.robv.android.xposed.callbacks;

/**
 * Compile-time stub of XC_LoadPackage (API 82). LSPosed injects the real class at runtime.
 */
public abstract class XC_LoadPackage extends XCallback {

    public XC_LoadPackage(LoadPackageParam param) { super(param); }

    public abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;

    /** Parameters passed to every load-package hook. */
    public static class LoadPackageParam extends Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public android.content.pm.ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
