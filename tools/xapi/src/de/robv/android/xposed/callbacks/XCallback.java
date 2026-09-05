package de.robv.android.xposed.callbacks;

/**
 * Compile-time stub of XCallback (API 82). LSPosed injects the real class at runtime.
 */
public abstract class XCallback {

    public XCallback(Param param) {}

    /** Base parameter type for all Xposed callbacks. */
    public static abstract class Param {
        public Object[] args;
    }
}
