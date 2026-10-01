// Phase-1 compile-only stub for the apkzlib type of the same name.
// Upstream: com.android.tools.build.apkzlib (Apache-2.0). Not a copy of upstream code --
// only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real implementation
// lands in Phase 2 when the patched apk is actually produced.
package com.android.tools.build.apkzlib.zip;

import java.util.Collections;

/** A read-only view over a source apk nested inside the output apk. */
public class NestedZip implements AutoCloseable {

    public StoredEntry get(String name) {
        return null;
    }

    public Iterable<StoredEntry> entries() {
        return Collections.emptyList();
    }

    public void addFileLink(String fromEntry, String toEntry) {
        // no-op in the stub
    }

    @Override
    public void close() {
    }
}
