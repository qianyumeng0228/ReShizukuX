// ReShizukuX: hand-written Parcelable mirroring the upstream struct at
// services/daemon-service/src/main/aidl/org/matrix/vector/ipc/ModuleCode.aidl @ e00c5c5.
//
// The field ORDER here is the on-wire order: the precompiled loader.dex reads a ModuleCode out of
// a LoadedModule with this exact sequence. Reordering a field breaks deserialization inside every
// already-patched app. For the minimum-viable manager we return empty module lists, so a
// ModuleCode is never actually written -- but the class must exist for the AIDL-generated stubs
// to link, and the order is kept faithful for when real modules are served.
package org.matrix.vector.ipc;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.SharedMemory;

import java.util.ArrayList;
import java.util.List;

public final class ModuleCode implements Parcelable {

    public List<SharedMemory> preLoadedDexes;
    public List<String> moduleClassNames;
    public List<String> moduleLibraryNames;
    public boolean legacy;
    public int targetApiVersion;
    public boolean autoHotReload;
    public boolean exceptionPassthrough;
    public String nativeLibraryDir;

    public ModuleCode() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // Exact AIDL-generated on-wire layout (verified against the precompiled loader.dex):
        // a leading int size-prefix placeholder, then the fields, then backfill the size. The
        // precompiled readFromParcel() reads the size first and bounds every subsequent read against
        // it -- omitting the size prefix makes the loader misread our first field as the parcelable
        // size and lands on the SharedMemory list read at a wrong offset, surfacing as
        // "Unable to create SharedMemory from a null FileDescriptor".
        int start = dest.dataPosition();
        dest.writeInt(0);
        // preLoadedDexes: count int, then per element a 0/1 presence int followed by the
        // SharedMemory's own writeToParcel() (which emits the file descriptor).
        if (preLoadedDexes == null) {
            dest.writeInt(-1);
        } else {
            dest.writeInt(preLoadedDexes.size());
            for (SharedMemory dex : preLoadedDexes) {
                if (dex == null) {
                    dest.writeInt(0);
                } else {
                    dest.writeInt(1);
                    dex.writeToParcel(dest, flags);
                }
            }
        }
        dest.writeStringList(moduleClassNames);
        dest.writeStringList(moduleLibraryNames);
        dest.writeInt(legacy ? 1 : 0);
        dest.writeInt(targetApiVersion);
        dest.writeInt(autoHotReload ? 1 : 0);
        dest.writeInt(exceptionPassthrough ? 1 : 0);
        dest.writeString(nativeLibraryDir);
        int end = dest.dataPosition();
        dest.setDataPosition(start);
        dest.writeInt(end - start);
        dest.setDataPosition(end);
    }

    public static final Creator<ModuleCode> CREATOR = new Creator<ModuleCode>() {
        @Override
        public ModuleCode createFromParcel(Parcel source) {
            ModuleCode code = new ModuleCode();
            code.preLoadedDexes = source.readArrayList(SharedMemory.class.getClassLoader());
            code.moduleClassNames = source.createStringArrayList();
            code.moduleLibraryNames = source.createStringArrayList();
            code.legacy = source.readInt() != 0;
            code.targetApiVersion = source.readInt();
            code.autoHotReload = source.readInt() != 0;
            code.exceptionPassthrough = source.readInt() != 0;
            code.nativeLibraryDir = source.readString();
            return code;
        }

        @Override
        public ModuleCode[] newArray(int size) {
            return new ModuleCode[size];
        }
    };
}
