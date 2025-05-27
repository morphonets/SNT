package sc.fiji.snt;

import java.lang.reflect.Method;

public final class ReflectionUnlocker {
    private static Method addOpens;
    private static Method addExports;
    private static Module unnamedModule;

    static {
        try {
            addOpens = Module.class.getDeclaredMethod(
                    "implAddOpens", String.class, Module.class);
            addOpens.setAccessible(true);

            addExports = Module.class.getDeclaredMethod(
                    "implAddExports", String.class, Module.class);
            addExports.setAccessible(true);

            unnamedModule = ReflectionUnlocker.class.getModule();
        } catch (final Exception ignored) {
            ignored.printStackTrace();
            //throw new RuntimeException("Failed to initialize ReflectionUnlocker", e);
        }
    }

    private ReflectionUnlocker() {}

    public static void unlockAll() {
        ModuleLayer.boot().modules().forEach(ReflectionUnlocker::unlockModule);
    }

    private static void unlockModule(Module m) {
        try {
            for (final String pkg : m.getPackages()) {
                try {
                    addOpens.invoke(m, pkg, unnamedModule);
                    addExports.invoke(m, pkg, unnamedModule);
                } catch (Exception e) {
                    // Continue with other packages
                }
            }
        } catch (final Exception e) {
            System.err.println("Failed to unlock module: " + m);
        }
    }
}
