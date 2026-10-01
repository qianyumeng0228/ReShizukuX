// Phase-1 compile-only stub for the manifest-editor (axml) type of the same name.
// Upstream: com.wind.meditor (https://github.com/Wind.../AXMLEditor, MIT). Not a copy of upstream
// code -- only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real
// implementation lands in Phase 2 when the patched apk is actually produced.
package com.wind.meditor.property;

import java.util.List;

/** The set of edits the manifest editor should apply. */
public class ModificationProperty {

    public void addUsesSdkAttribute(AttributeItem attribute) {
    }

    public void addApplicationAttribute(AttributeItem attribute) {
    }

    public void addManifestAttribute(AttributeItem attribute) {
    }

    public void addMetaData(MetaData metaData) {
    }

    public void addUsesPermission(String permission) {
    }

    public void addProvider(List<AttributeItem> attributes, String action) {
    }

    /** A single {@code <meta-data>} entry. */
    public static class MetaData {
        public final String name;
        public final String value;

        public MetaData(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
