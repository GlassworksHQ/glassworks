package dev.glassworks.stockprobe;

/** Optional JNI wrapper for Linux FS_IOC_FIEMAP (compiled when Android NDK is available). */
public final class Fiemap {
    private static final boolean LOADED;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("fiemap");
            ok = true;
        } catch (Throwable ignored) {
            ok = false;
        }
        LOADED = ok;
    }

    public static boolean isAvailable() {
        return LOADED;
    }

    /** Returns JSON describing the file's physical extents on block storage, or an error JSON object. */
    public static native String queryJson(String path);
}
