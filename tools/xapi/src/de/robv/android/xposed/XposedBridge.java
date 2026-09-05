package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Compile-time stub of the Xposed API.
 *
 * At runtime LSPosed injects the REAL implementation of these classes into the target
 * process, so this jar must never be bundled into the APK - the module build uses it
 * with `compileOnly`. Member signatures mirror XposedBridge API 82 so behavior is
 * identical when running under LSPosed.
 */
public final class XposedBridge {

    private XposedBridge() {}

    /** Runtime Xposed version constant. */
    public static int XPOSED_BRIDGE_VERSION = 82;

    /** Log a line to the Xposed log (visible in LSPosed Manager and logcat LSPosed-Bridge). */
    public static void log(String text) {
        throw new IllegalStateException("stub - provided by the Xposed runtime");
    }

    /** Log a throwable with stack trace. */
    public static void log(Throwable t) {
        throw new IllegalStateException("stub - provided by the Xposed runtime");
    }

    /**
     * Hook an arbitrary Method/Constructor. The real runtime replaces the method body
     * so beforeHookedMethod/afterHookedMethod are invoked around every call.
     *
     * NOTE: the real LSPosed API returns a single {@link XC_MethodHook.Unhook}
     * (NOT a Set — unlike hookAllConstructors/hookAllMethods). The return type is
     * part of the JVM method descriptor, so getting this wrong compiles fine but
     * throws NoSuchMethodError inside the target process.
     */
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new IllegalStateException("stub - provided by the Xposed runtime");
    }

    /** Hook every constructor of {@code hookClass}. */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        throw new IllegalStateException("stub - provided by the Xposed runtime");
    }

    /** Hook all methods with the given name (any signature). */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new IllegalStateException("stub - provided by the Xposed runtime");
    }

    /** Invoke the original method implementation, skipping all hooks. */
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException,
            InvocationTargetException {
        throw new IllegalStateException("stub - provided by the Xposed runtime");
    }
}
