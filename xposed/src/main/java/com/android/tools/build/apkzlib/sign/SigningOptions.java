// Phase-1 compile-only stub for the apkzlib type of the same name.
// Upstream: com.android.tools.build.apkzlib (Apache-2.0). Not a copy of upstream code --
// only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real implementation
// lands in Phase 2 when the patched apk is actually produced.
package com.android.tools.build.apkzlib.sign;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/** Options controlling how a {@link SigningExtension} signs an apk. */
public class SigningOptions {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        public Builder setMinSdkVersion(int minSdkVersion) {
            return this;
        }

        public Builder setV2SigningEnabled(boolean enabled) {
            return this;
        }

        public Builder setCertificates(X509Certificate[] certificates) {
            return this;
        }

        public Builder setKey(PrivateKey key) {
            return this;
        }

        public SigningOptions build() {
            return new SigningOptions();
        }
    }
}
