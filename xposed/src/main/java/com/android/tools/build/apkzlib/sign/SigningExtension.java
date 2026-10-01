// Phase-1 compile-only stub for the apkzlib type of the same name.
// Upstream: com.android.tools.build.apkzlib (Apache-2.0). Not a copy of upstream code --
// only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real implementation
// lands in Phase 2 when the patched apk is actually produced.
package com.android.tools.build.apkzlib.sign;

import com.android.tools.build.apkzlib.zip.ZFile;

import java.io.IOException;

/** Adds an APK Signature Scheme v2 block to a {@link ZFile} as it is closed. */
public class SigningExtension {

    public SigningExtension(SigningOptions options) {
    }

    public void register(ZFile zip) throws IOException {
        // no-op in the stub
    }
}
