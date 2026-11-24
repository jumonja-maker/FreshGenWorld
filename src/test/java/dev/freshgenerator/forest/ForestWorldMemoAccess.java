package dev.freshgenerator.forest;

/** Test access to the package-private memo key. */
public final class ForestWorldMemoAccess {
    private ForestWorldMemoAccess() { }
    public static long key(int qx, int qy, int qz) { return ForestWorldMemo.quartKey(qx, qy, qz); }
}
