package dev.freshgenerator.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Broken invariants during world generation (code audit 24.11 K-4). An exception in chunk generation stops the
 * server, so outside tests a broken invariant is reported once and the caller falls back to a safe answer (skip the
 * tree, no drainage, no glacier); with the system property freshgenerator.strict=true (the unit tests) it throws. */
public final class Diagnostics {
    private Diagnostics() { }
    /** Where warnings go; the game side replaces it with its logger. */
    public static volatile Consumer<String> sink = message -> System.err.println("[FreshGenerator] " + message);
    public static final boolean STRICT = Boolean.getBoolean("freshgenerator.strict");
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    /** Reports a message once per key. */
    public static void warnOnce(String key, String message) {
        if (SEEN.add(key)) sink.accept(message);
    }
    /** A broken invariant: throws in strict mode, otherwise reports once (the caller then falls back). */
    public static void broken(String key, String message) {
        if (STRICT) throw new IllegalStateException(message);
        warnOnce(key, message + " (reported once; generation continues with a safe fallback)");
    }
}
