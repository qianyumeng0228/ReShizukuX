// Phase-1 compile-only stub for the manifest-editor (axml) type of the same name.
// Upstream: com.wind.meditor (https://github.com/Wind.../AXMLEditor, MIT). Not a copy of upstream
// code -- only the narrow surface io.reshizukux.xposed.patcher.util.ManifestParser touches. Real
// implementation lands in Phase 2 when the patched apk is actually produced.
package com.wind.meditor.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Small stream helpers used by the manifest parser. */
public final class Utils {

    private Utils() {
    }

    public static byte[] getBytesFromInputStream(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }
}
