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
        dest.writeList(preLoadedDexes);
        dest.writeStringList(moduleClassNames);
        dest.writeStringList(moduleLibraryNames);
        dest.writeInt(legacy ? 1 : 0);
        dest.writeInt(targetApiVersion);
        dest.writeInt(autoHotReload ? 1 : 0);
        dest.writeInt(exceptionPassthrough ? 1 : 0);
        dest.writeString(nativeLibraryDir);
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
