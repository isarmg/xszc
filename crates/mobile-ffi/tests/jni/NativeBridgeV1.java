package org.sarmg.xszc;

import java.nio.file.Files;
import java.nio.file.Path;

/** Calls the actual shared library; no mocked JNI environment or return values. */
public final class NativeBridgeV1 {
    static { System.loadLibrary("xszc_mobile"); }
    private static native int abiRevision();
    private static native int panicProbe();
    private static native long open(String path, String config);
    private static native void close(long handle);
    private static native boolean needs(long handle, String asset, String resource, long modified);
    private static native String transfer(long handle, String input);
    private static native String enqueue(long handle, String input);
    private static native String next(long handle, String staging);
    private static native String markUpload(long handle, String job, String upload);
    private static native String markPart(long handle, String job, int index);
    private static native String markComplete(long handle, String job);
    private static native String markFailed(long handle, String job, String message, boolean retryable);
    private static native String stats(long handle);

    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable error) {
            if (!type.isInstance(error)) throw new AssertionError("wrong native exception", error);
            if (error.getMessage().contains("private-secret")) throw new AssertionError("native error leaked input");
            return;
        }
        throw new AssertionError("native failure returned a normal value");
    }

    public static void main(String[] args) throws Exception {
        if (abiRevision() != 1) throw new AssertionError("native ABI mismatch");
        try {
            panicProbe();
            throw new AssertionError("Rust panic returned a success value");
        } catch (RuntimeException error) {
            if (!"native status 255: internal panic".equals(error.getMessage())) {
                throw new AssertionError("Rust panic was not mapped to status 255", error);
            }
        }
        expect(IllegalStateException.class, () -> stats(0));
        expect(IllegalStateException.class, () -> close(0));
        expect(IllegalArgumentException.class, () -> needs(0, null, "resource", 1));
        expect(IllegalArgumentException.class, () -> needs(0, "\uD800", "resource", 1));
        expect(IllegalArgumentException.class, () -> needs(0, "x".repeat(4097), "resource", 1));
        expect(IllegalArgumentException.class, () -> markPart(0, "job", -1));
        expect(IllegalStateException.class, () -> markUpload(0, "job", "upload"));
        expect(IllegalStateException.class, () -> markComplete(0, "job"));
        expect(IllegalStateException.class, () -> markFailed(0, "job", "private-secret", true));
        expect(IllegalArgumentException.class, () -> enqueue(0, "private-secret"));
        expect(IllegalArgumentException.class, () -> next(0, "private-secret"));
        Path absent = Path.of(args[0], "absent", "client-v1.sqlite");
        expect(IllegalArgumentException.class, () -> open(absent.toString(), "private-secret"));
        if (Files.exists(absent.getParent())) throw new AssertionError("invalid config wrote state");
        String path = Path.of(args[0], "client-v1.sqlite").toString();
        String config = "{\"product\":\"xszc\",\"application_version\":\"1.0.0\",\"revision\":1,\"state_epoch\":\"xszc-mobile-v1\",\"part_size\":16777216}";
        long first = open(path, config);
        if (first == 0 || !needs(first, "中文😀", "resource", 1)) throw new AssertionError("valid Unicode query failed");
        if (!stats(first).contains("\"ok\":true")) throw new AssertionError("invalid success envelope");
        String command = config.substring(0, config.lastIndexOf(",\"part_size\"")) + ",\"command\":{\"op\":\"batches\"}}";
        if (!transfer(first, command).contains("\"value\":[]")) throw new AssertionError("batch command failed");
        expect(IllegalArgumentException.class, () -> transfer(first, "{\"unknown\":true}"));
        close(first);
        expect(IllegalStateException.class, () -> close(first));
        long second = open(path, config);
        if (second == first) throw new AssertionError("stale handle reused");
        expect(IllegalStateException.class, () -> stats(first));
        close(second);
        System.out.println("JNI native library: explicit exceptions, Unicode, lengths and stale handles passed");
    }
}
