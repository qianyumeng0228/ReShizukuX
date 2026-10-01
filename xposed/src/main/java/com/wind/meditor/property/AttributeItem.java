// Phase-1 compile-only stub for the manifest-editor (axml) type of the same name.
// Upstream: com.wind.meditor (https://github.com/Wind.../AXMLEditor, MIT). Not a copy of upstream
// code -- only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real
// implementation lands in Phase 2 when the patched apk is actually produced.
package com.wind.meditor.property;

/** A single (name, value) manifest attribute to set. */
public class AttributeItem {

    private final String name;
    private final Object value;

    public AttributeItem(String name, int value) {
        this.name = name;
        this.value = value;
    }

    public AttributeItem(String name, Object value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public Object getValue() {
        return value;
    }
}
