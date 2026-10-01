// Phase-1 compile-only stub for the manifest-editor (axml) type of the same name.
// Upstream: com.wind.meditor (https://github.com/Wind.../AXMLEditor, MIT). Not a copy of upstream
// code -- only the narrow surface io.reshizukux.xposed.patcher.ApkPatcher touches. Real
// implementation lands in Phase 2 when the patched apk is actually produced.
package com.wind.meditor.core;

import com.wind.meditor.property.ModificationProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Rewrites a binary {@code AndroidManifest.xml} according to a {@link ModificationProperty}. */
public class ManifestEditor {

    public ManifestEditor(InputStream srcManifest, OutputStream dstManifest, ModificationProperty property) {
    }

    public void processManifest() throws IOException {
        throw new UnsupportedOperationException("Phase 1 stub: ManifestEditor.processManifest() is not implemented");
    }
}
