package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;

/**
 * Compile-time stub of XposedHelpers (API 82).
 *
 * At runtime LSPosed injects the real implementation; this stub only needs to make
 * call sites compile with identical signatures (including thrown checked exceptions).
 */
public final class XposedHelpers {

    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundError {
        throw new IllegalStateException("stub");
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        throw new IllegalStateException("stub");
    }

    public static Object newInstance(Class<?> clazz, Object... args)
            throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        throw new IllegalStateException("stub");
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        throw new IllegalStateException("stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new IllegalStateException("stub");
    }

    public static int getIntField(Object obj, String fieldName) {
        throw new IllegalStateException("stub");
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        throw new IllegalStateException("stub");
    }

    public static Object callMethod(Object obj, String methodName, Object... args)
            throws InvocationTargetException {
        throw new IllegalStateException("stub");
    }

    /**
     * Hook a method by (class, name) without pre-resolving it.
     * The last vararg element must be the {@link XC_MethodHook} callback; the rest
     * are the hooked method's parameter types. Mirrors the real API 82 signature.
     */
    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback)
            throws NoSuchMethodError {
        throw new IllegalStateException("stub");
    }

    /** Thrown when a class cannot be found - mirrors the real checked exception. */
    public static class ClassNotFoundError extends Error {
        public ClassNotFoundError(Throwable cause) { super(cause); }
        public ClassNotFoundError(String detailMessage) { super(detailMessage); }
    }

    /** Returned by hook helpers so callers can unhook. Mirrors XC_MethodHook.Unhook. */
    public static interface OnHookEventListener<T extends XC_MethodHook.MethodHookParam> {
        void onHookEvent(T param);
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException,
            InvocationTargetException {
        throw new IllegalStateException("stub");
    }
}
