// Phase-1 compile-only stub for the apkzlib type of the same name.
// Upstream: com.android.tools.build.apkzlib (Apache-2.0). Not a copy of upstream code --
// only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real implementation
// lands in Phase 2 when the patched apk is actually produced.
package com.android.tools.build.apkzlib.zip;

/** Options controlling how a {@link ZFile} is written. */
public class ZFileOptions {

    public ZFileOptions setAlignmentRule(AlignmentRule rule) {
        return this;
    }
}
