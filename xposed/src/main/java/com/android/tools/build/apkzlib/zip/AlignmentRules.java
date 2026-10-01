// Phase-1 compile-only stub for the apkzlib type of the same name.
// Upstream: com.android.tools.build.apkzlib (Apache-2.0). Not a copy of upstream code --
// only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real implementation
// lands in Phase 2 when the patched apk is actually produced.
package com.android.tools.build.apkzlib.zip;

/** Factories for {@link AlignmentRule}. */
public final class AlignmentRules {

    private AlignmentRules() {
    }

    /** Align entries whose name ends with {@code suffix} to {@code alignment} bytes. */
    public static AlignmentRule constantForSuffix(String suffix, int alignment) {
        return new AlignmentRule();
    }

    /** Combines several rules into one. */
    public static AlignmentRule compose(AlignmentRule... rules) {
        return new AlignmentRule();
    }
}
