package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Compile-time stub of XC_MethodHook (API 82). LSPosed injects the real class at runtime.
 */
public abstract class XC_MethodHook {

    public static final int PRIORITY_DEFAULT = 0;
    public static final int PRIORITY_LOWEST = -0x80000000;
    public static final int PRIORITY_HIGHEST = 0x7fffffff;

    public XC_MethodHook() {}

    public XC_MethodHook(int priority) {}

    /** Called before the hooked method body runs. */
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    /** Called after the hooked method body ran (or was replaced). */
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    /** Parameter object passed to the hooks; fields mirror the real API. */
    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object result = null;
        public Throwable throwable = null;
        public boolean returnEarly = false;

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public void setThrowable(Throwable throwable) { this.throwable = throwable; }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    /** Handle returned when hooking, allowing to remove the hook again. */
    public static class Unhook {
        private final Member hookMethod;
        public Unhook(Member hookMethod) { this.hookMethod = hookMethod; }
        public Member getHookedMethod() { return hookMethod; }
        public void unhook() { throw new IllegalStateException("stub"); }
    }
}
