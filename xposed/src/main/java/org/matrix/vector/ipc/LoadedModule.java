// ReShizukuX: hand-written Parcelable mirroring the upstream struct at
// services/daemon-service/src/main/aidl/org/matrix/vector/ipc/LoadedModule.aidl @ e00c5c5.
//
// The field ORDER here is the on-wire order the precompiled loader.dex expects. For the
// minimum-viable manager we serve empty module lists, so a LoadedModule is never actually
// written today; the order is kept faithful for when real modules are scoped to an app.
package org.matrix.vector.ipc;

import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.os.Parcelable;

public final class LoadedModule implements Parcelable {

    /** The module app's package name, which is the module's identity everywhere. */
    public String packageName;
    /** The module app's app id (uid without the user component). */
    public int appId;
    /** The module app's version code as PackageManager reports it; 0 when unknown. */
    public long versionCode;
    /** Path to the module APK, used to build the native library search path. */
    public String apkPath;
    /** The generation of code to load. */
    public ModuleCode code;
    /** The module app's own ApplicationInfo. */
    public ApplicationInfo applicationInfo;
    /** What XposedInterface's remote preferences/files calls go through. */
    public IModuleService service;

    public LoadedModule() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // Same AIDL-generated size-prefix layout as ModuleCode (verified against loader.dex):
        // leading int size placeholder, fields, then backfill size. code/applicationInfo are nested
        // parcelables written with a 0/1 presence int each (the loader reads them via
        // _Parcel.readTypedObject). writeParcelable()/writeStrongBinder-as-polymorphic would emit a
        // concrete class name string first and break the alignment.
        int start = dest.dataPosition();
        dest.writeInt(0);
        dest.writeString(packageName);
        dest.writeInt(appId);
        dest.writeLong(versionCode);
        dest.writeString(apkPath);
        if (code != null) {
            dest.writeInt(1);
            code.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        if (applicationInfo != null) {
            dest.writeInt(1);
            applicationInfo.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeStrongBinder(service != null ? service.asBinder() : null);
        int end = dest.dataPosition();
        dest.setDataPosition(start);
        dest.writeInt(end - start);
        dest.setDataPosition(end);
    }

    public static final Creator<LoadedModule> CREATOR = new Creator<LoadedModule>() {
        @Override
        public LoadedModule createFromParcel(Parcel source) {
            LoadedModule module = new LoadedModule();
            module.packageName = source.readString();
            module.appId = source.readInt();
            module.versionCode = source.readLong();
            module.apkPath = source.readString();
            module.code = source.readParcelable(ModuleCode.class.getClassLoader());
            module.applicationInfo = source.readParcelable(ApplicationInfo.class.getClassLoader());
            module.service = IModuleService.Stub.asInterface(source.readStrongBinder());
            return module;
        }

        @Override
        public LoadedModule[] newArray(int size) {
            return new LoadedModule[size];
        }
    };
}
