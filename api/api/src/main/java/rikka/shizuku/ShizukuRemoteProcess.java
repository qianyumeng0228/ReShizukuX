package rikka.shizuku;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IRemoteProcess;

public class ShizukuRemoteProcess extends Process implements Parcelable {

    private static final Set<ShizukuRemoteProcess> CACHE = Collections.synchronizedSet(new ArraySet<>());

    private static final String TAG = "ShizukuRemoteProcess";

    private IRemoteProcess remote;
    private OutputStream os;
    private InputStream is;

    ShizukuRemoteProcess(IRemoteProcess remote) {
        // 当特权进程无法启动时（例如命令派生失败，或过期服务器拒绝了调用），
        // 服务会返回 null。这里抛出清晰、可捕获的异常，而不是在 remote.asBinder()
        // 上触发 NPE（SHIZUKUX-85）。
        if (remote == null) {
            throw new IllegalStateException(
                    "Shizuku returned a null remote process for newProcess() — the privileged service could not start the command");
        }
        this.remote = remote;
        try {
            this.remote.asBinder().linkToDeath((IBinder.DeathRecipient) () -> {
                this.remote = null;
                Log.v(TAG, "remote process is dead");

                CACHE.remove(ShizukuRemoteProcess.this);
            }, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "linkToDeath", e);
        }

        // 必须持有 binder 对象的引用
        CACHE.add(this);
    }

    @Override
    public OutputStream getOutputStream() {
        if (os == null) {
            try {
                os = new ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream());
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        return os;
    }

    @Override
    public InputStream getInputStream() {
        if (is == null) {
            try {
                is = new ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream());
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        return is;
    }

    @Override
    public InputStream getErrorStream() {
        try {
            return new ParcelFileDescriptor.AutoCloseInputStream(remote.getErrorStream());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int waitFor() throws InterruptedException {
        try {
            return remote.waitFor();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int exitValue() {
        try {
            return remote.exitValue();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void destroy() {
        try {
            remote.destroy();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean alive() {
        try {
            return remote.alive();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean waitForTimeout(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            return remote.waitForTimeout(timeout, unit.toString());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public IBinder asBinder() {
        return remote.asBinder();
    }

    private ShizukuRemoteProcess(Parcel in) {
        remote = IRemoteProcess.Stub.asInterface(in.readStrongBinder());
    }

    public static final Creator<ShizukuRemoteProcess> CREATOR = new Creator<ShizukuRemoteProcess>() {
        @Override
        public ShizukuRemoteProcess createFromParcel(Parcel in) {
            return new ShizukuRemoteProcess(in);
        }

        @Override
        public ShizukuRemoteProcess[] newArray(int size) {
            return new ShizukuRemoteProcess[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(remote.asBinder());
    }
}
