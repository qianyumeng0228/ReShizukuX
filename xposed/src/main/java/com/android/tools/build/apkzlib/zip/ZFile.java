// Phase-1 compile-only stub for the apkzlib type of the same name.
// Upstream: com.android.tools.build.apkzlib (Apache-2.0). Not a copy of upstream code --
// only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real implementation
// lands in Phase 2 when the patched apk is actually produced.
package com.android.tools.build.apkzlib.zip;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;

/** The output apk being built. */
public class ZFile implements AutoCloseable {

    public static ZFile openReadWrite(File file, ZFileOptions options) throws IOException {
        throw new UnsupportedOperationException("Phase 1 stub: ZFile.openReadWrite() is not implemented");
    }

    public static ZFile openReadOnly(File file) throws IOException {
        throw new UnsupportedOperationException("Phase 1 stub: ZFile.openReadOnly() is not implemented");
    }

    public NestedZip addNestedZip(Function<Object, String> entryNaming, File source, boolean dedupe) {
        return new NestedZip();
    }

    public StoredEntry get(String name) {
        return null;
    }

    public void add(String name, InputStream data) throws IOException {
        throw new UnsupportedOperationException("Phase 1 stub: ZFile.add(name, data) is not implemented");
    }

    public void add(String name, InputStream data, boolean deflate) throws IOException {
        throw new UnsupportedOperationException("Phase 1 stub: ZFile.add(name, data, deflate) is not implemented");
    }

    public void realign() {
        // no-op in the stub
    }

    @Override
    public void close() {
    }
}
