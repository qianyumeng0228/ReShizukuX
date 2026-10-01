// Phase-1 compile-only stub for the manifest-editor (axml) type of the same name.
// Upstream: com.wind.meditor (https://github.com/Wind.../AXMLEditor, MIT). Not a copy of upstream
// code -- only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real
// implementation lands in Phase 2 when the patched apk is actually produced.
package com.wind.meditor.utils;

/** Well-known Android manifest attribute / node name strings used by the editor. */
public class NodeValue {

    public static class UsesSDK {
        public static final String MIN_SDK_VERSION = "minSdkVersion";
        public static final String TARGET_SDK_VERSION = "targetSdkVersion";
    }

    public static class Manifest {
        public static final String VERSION_CODE = "versionCode";
    }

    public static class Application {
        public static final String DEBUGGABLE = "debuggable";
        public static final String LABEL = "label";
        public static final String EXTRACTNATIVELIBS = "extractNativeLibs";
        public static final String NAME = "name";

        public static class Provider {
            public static final String AUTHORITIES = "authorities";
        }

        public static class Component {
            public static final String PERMISSION = "permission";
        }
    }
}
