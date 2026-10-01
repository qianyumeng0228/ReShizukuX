// Phase-1 compile-only stub for the apkzlib type of the same name.
// Upstream: com.android.tools.build.apkzlib (Apache-2.0). Not a copy of upstream code --
// only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real implementation
// lands in Phase 2 when the patched apk is actually produced.
package com.android.tools.build.apkzlib.zip;

import java.io.IOException;
import java.io.InputStream;

/** One entry inside a (possibly nested) zip file. */
public class StoredEntry {

    private final String name;

    public StoredEntry(String name) {
        this.name = name;
    }

    public CentralDirectoryHeader getCentralDirectoryHeader() {
        return new CentralDirectoryHeader(name);
    }

    public InputStream open() throws IOException {
        throw new UnsupportedOperationException("Phase 1 stub: apkzlib StoredEntry.open() is not implemented");
    }
}
