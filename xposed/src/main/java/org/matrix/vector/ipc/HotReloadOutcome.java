// ReShizukuX: minimal Parcelable placeholder for the upstream HotReloadOutcome.
//
// The upstream struct carries the result of a hot-reload the manager asked for. In the
// minimum-viable Manager-mode implementation the manager never drives a hot reload, so a
// HotReloadOutcome is never actually marshalled -- this type exists only so the AIDL-generated
// IHotReloadOutcomeReceiver stub links. When real hot reload is wired up, expand the fields to
// match the upstream definition.
package org.matrix.vector.ipc;

import android.os.Parcel;
import android.os.Parcelable;

public final class HotReloadOutcome implements Parcelable {

    public HotReloadOutcome() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // No fields marshalled yet; see class comment.
    }

    public static final Creator<HotReloadOutcome> CREATOR = new Creator<HotReloadOutcome>() {
        @Override
        public HotReloadOutcome createFromParcel(Parcel source) {
            return new HotReloadOutcome();
        }

        @Override
        public HotReloadOutcome[] newArray(int size) {
            return new HotReloadOutcome[size];
        }
    };
}
