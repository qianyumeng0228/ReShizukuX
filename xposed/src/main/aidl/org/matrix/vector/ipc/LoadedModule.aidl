// ReShizukuX: vendored from JingMatrix/Vector @ e00c5c5.
// Forward declaration; the concrete Parcelable lives at
// xposed/src/main/java/org/matrix/vector/ipc/LoadedModule.java. The field ORDER there must match
// the upstream struct definition so a List<LoadedModule> parcel written by the manager reads
// back correctly inside the precompiled loader.dex.
package org.matrix.vector.ipc;

parcelable LoadedModule;
