// Phase-1 compile-only stub for the binary-axml parser of the same name (pxb.android.axml,
// from the AXML/AXMLEditor ecosystem). Not a copy of upstream code -- only the narrow surface
// io.reshizukux.xposed.patcher.util.ManifestParser touches. Real implementation lands in Phase 2
// when the patched apk is actually produced.
package pxb.android.axml;

/** Pull parser over a compiled (binary) AndroidManifest.xml. */
public class AxmlParser {

    public static final int END_FILE = 0;
    public static final int START_TAG = 1;
    public static final int END_TAG = 2;

    public AxmlParser(byte[] xml) {
    }

    /** Advances to the next event; returns one of the {@code *_TAG} / {@code END_FILE} constants. */
    public int next() {
        return END_FILE;
    }

    public int getAttributeCount() {
        return 0;
    }

    public String getAttrName(int index) {
        return null;
    }

    public int getAttrResId(int index) {
        return 0;
    }

    /** The name of the current start/end tag. */
    public String getName() {
        return null;
    }

    public Object getAttrValue(int index) {
        return null;
    }
}
