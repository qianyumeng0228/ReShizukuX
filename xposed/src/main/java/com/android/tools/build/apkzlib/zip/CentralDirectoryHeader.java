// Phase-1 compile-only stub for the apkzlib type of the same name.
// Upstream: com.android.tools.build.apkzlib (Apache-2.0). Not a copy of upstream code --
// only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real implementation
// lands in Phase 2 when the patched apk is actually produced.
package com.android.tools.build.apkzlib.zip;

/** The central-directory header of one zip entry. */
public class CentralDirectoryHeader {

    private final String name;

    public CentralDirectoryHeader(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
