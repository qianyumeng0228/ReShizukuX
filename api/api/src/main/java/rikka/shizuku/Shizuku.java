package rikka.shizuku;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;

public class Shizuku {

    private static volatile IBinder binder;
    private static volatile IShizukuService service;

    private static volatile int serverUid = -1;
    private static volatile int serverApiVersion = -1;
    private static volatile int serverPatchVersion = -1;
    private static volatile String serverContext = null;
    private static volatile boolean permissionGranted = false;
    private static volatile boolean shouldShowRequestPermissionRationale = false;
    private static volatile boolean preV11 = false;
    private static volatile boolean binderReady = false;

    private static final IShizukuApplication SHIZUKU_APPLICATION = new IShizukuApplication.Stub() {

        @Override
        public void bindApplication(Bundle data) {
            serverUid = data.getInt(BIND_APPLICATION_SERVER_UID, -1);
            serverApiVersion = data.getInt(BIND_APPLICATION_SERVER_VERSION, -1);
            serverPatchVersion = data.getInt(BIND_APPLICATION_SERVER_PATCH_VERSION, -1);
            serverContext = data.getString(BIND_APPLICATION_SERVER_SECONTEXT);
            permissionGranted = data.getBoolean(BIND_APPLICATION_PERMISSION_GRANTED, false);
            shouldShowRequestPermissionRationale = data.getBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);

            scheduleBinderReceivedListeners();
        }

        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            boolean allowed = data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false);
            scheduleRequestPermissionResultListener(requestCode, allowed ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
        }

        @Override
        public void dispatchLog(String appName, String packageName, String action) {
            scheduleLogListener(appName, packageName, action);
        }

        @Override
        public void dispatchSentryEvent(String eventJson) {
            scheduleSentryEventListener(eventJson);
        }

        @Override
        public void showPermissionConfirmation(int requestUid, int requestPid, String requestPackageName, int requestCode) {
            // 非应用端调用
        }
    };

    private static final IBinder.DeathRecipient DEATH_RECIPIENT = () -> {
        binderReady = false;
        onBinderReceived(null, null);
    };

    private static boolean attachApplicationV13(IBinder binder, String packageName) throws RemoteException {
        boolean result;

        Bundle args = new Bundle();
        args.putInt(ATTACH_APPLICATION_API_VERSION, ShizukuApiConstants.SERVER_VERSION);
        args.putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            data.writeStrongBinder(SHIZUKU_APPLICATION.asBinder());
            data.writeInt(1);
            args.writeToParcel(data, 0);
            result = binder.transact(17 /*IShizukuService.Stub.TRANSACTION_attachApplication*/, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }

        return result;
    }

    private static boolean attachApplicationV11(IBinder binder, String packageName) throws RemoteException {
        boolean result;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            data.writeStrongBinder(SHIZUKU_APPLICATION.asBinder());
            data.writeString(packageName);
            result = binder.transact(14 /*IShizukuService.Stub.TRANSACTION_attachApplication*/, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }

        return result;
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void onBinderReceived(@Nullable IBinder newBinder, String packageName) {
        if (binder == newBinder) return;

        if (newBinder == null) {
            binder = null;
            service = null;
            serverUid = -1;
            serverApiVersion = -1;
            serverContext = null;

            scheduleBinderDeadListeners();
        } else {
            if (binder != null) {
                binder.unlinkToDeath(DEATH_RECIPIENT, 0);
            }
            binder = newBinder;
            service = IShizukuService.Stub.asInterface(newBinder);

            try {
                binder.linkToDeath(DEATH_RECIPIENT, 0);
            } catch (Throwable e) {
                Log.i("ShizukuApplication", "attachApplication");
            }

            try {
                if (!attachApplicationV13(binder, packageName) && !attachApplicationV11(binder, packageName)) {
                    preV11 = true;
                }
                Log.i("ShizukuApplication", "attachApplication");
            } catch (Throwable e) {
                Log.w("ShizukuApplication", Log.getStackTraceString(e));
            }

            if (preV11) {
                binderReady = true;
                scheduleBinderReceivedListeners();
            }
        }
    }

    public interface OnBinderReceivedListener {
        void onBinderReceived();
    }

    public interface OnBinderDeadListener {
        void onBinderDead();
    }

    public interface OnRequestPermissionResultListener {

        /**
         * 请求权限结果的回调。
         *
         * @param requestCode 传入 {@link #requestPermission(int)} 的请求码。
         * @param grantResult 授权结果，为 {@link android.content.pm.PackageManager#PERMISSION_GRANTED}
         *                    或 {@link android.content.pm.PackageManager#PERMISSION_DENIED}。
         */
        void onRequestPermissionResult(int requestCode, int grantResult);
    }

    public interface OnLogListener {
        void onLog(String appName, String packageName, String action);
    }

    public interface OnSentryEventListener {
        void onSentryEvent(String eventJson);
    }

    private static class ListenerHolder<T> {

        private final T listener;
        private final Handler handler;

        private ListenerHolder(@NonNull T listener, @Nullable Handler handler) {
            this.listener = listener;
            this.handler = handler;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ListenerHolder<?> that = (ListenerHolder<?>) o;
            return Objects.equals(listener, that.listener) && Objects.equals(handler, that.handler);
        }

        @Override
        public int hashCode() {
            return Objects.hash(listener, handler);
        }
    }

    private static final List<ListenerHolder<OnBinderReceivedListener>> RECEIVED_LISTENERS = new ArrayList<>();
    private static final List<ListenerHolder<OnBinderDeadListener>> DEAD_LISTENERS = new ArrayList<>();
    private static final List<ListenerHolder<OnRequestPermissionResultListener>> PERMISSION_LISTENERS = new ArrayList<>();
    private static final List<ListenerHolder<OnLogListener>> LOG_LISTENERS = new ArrayList<>();
    private static final List<ListenerHolder<OnSentryEventListener>> SENTRY_EVENT_LISTENERS = new ArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * 添加一个在收到 binder 时被调用的监听器。
     * <p>
     * 只有在收到 binder 后才能使用 Shizuku API，否则将抛出
     * {@link IllegalStateException}。
     *
     * <p>注意：</p>
     * <ul>
     * <li>监听器将在主线程中调用。</li>
     * <li>监听器可能被多次调用。例如，用户在应用运行时重启了 Shizuku。</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        addBinderReceivedListener(listener, null);
    }

    /**
     * 添加一个在收到 binder 时被调用的监听器。
     * <p>
     * 只有在收到 binder 后才能使用 Shizuku API，否则将抛出
     * {@link IllegalStateException}。
     *
     * <p>注意：</p>
     * <ul>
     * <li>监听器可能被多次调用。例如，用户在应用运行时重启了 Shizuku。</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     * @param handler  监听器的调用线程。若为 null，则在主线程中调用。
     */
    public static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener, @Nullable Handler handler) {
        addBinderReceivedListener(Objects.requireNonNull(listener), false, handler);
    }

    /**
     * 与 {@link #addBinderReceivedListener(OnBinderReceivedListener)} 相同，但若 binder
     * 已收到，则立即调用该监听器。
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addBinderReceivedListenerSticky(@NonNull OnBinderReceivedListener listener) {
        addBinderReceivedListenerSticky(Objects.requireNonNull(listener), null);
    }

    /**
     * 与 {@link #addBinderReceivedListener(OnBinderReceivedListener)} 相同，但若 binder
     * 已收到，则立即调用该监听器。
     *
     * @param listener OnBinderReceivedListener
     * @param handler  监听器的调用线程。若为 null，则在主线程中调用。
     */
    public static void addBinderReceivedListenerSticky(@NonNull OnBinderReceivedListener listener, @Nullable Handler handler) {
        addBinderReceivedListener(Objects.requireNonNull(listener), true, handler);
    }

    private static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener, boolean sticky, @Nullable Handler handler) {
        if (sticky && binderReady) {
            if (handler != null) {
                handler.post(listener::onBinderReceived);
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onBinderReceived();
            } else {
                MAIN_HANDLER.post(listener::onBinderReceived);
            }
        }
        synchronized (RECEIVED_LISTENERS) {
            RECEIVED_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    /**
     * 移除由 {@link #addBinderReceivedListener(OnBinderReceivedListener)}
     * 或 {@link #addBinderReceivedListenerSticky(OnBinderReceivedListener)} 添加的监听器。
     *
     * @param listener OnBinderReceivedListener
     * @return 监听器是否已被移除。
     */
    public static boolean removeBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return RECEIVED_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    private static void scheduleBinderReceivedListeners() {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnBinderReceivedListener> holder : RECEIVED_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(holder.listener::onBinderReceived);
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onBinderReceived();
                    } else {
                        MAIN_HANDLER.post(holder.listener::onBinderReceived);
                    }
                }
            }
        }
        binderReady = true;
    }

    /**
     * 添加一个在 binder 失效（dead）时被调用的监听器。
     * <p>注意：</p>
     * <ul>
     * <li>监听器将在主线程中调用。</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addBinderDeadListener(@NonNull OnBinderDeadListener listener) {
        addBinderDeadListener(listener, null);
    }

    /**
     * 添加一个在 binder 失效（dead）时被调用的监听器。
     *
     * @param listener OnBinderReceivedListener
     * @param handler  监听器的调用线程。若为 null，则在主线程中调用。
     */
    public static void addBinderDeadListener(@NonNull OnBinderDeadListener listener, @Nullable Handler handler) {
        synchronized (RECEIVED_LISTENERS) {
            DEAD_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    /**
     * 移除由 {@link #addBinderDeadListener(OnBinderDeadListener)} 添加的监听器。
     *
     * @param listener OnBinderDeadListener
     * @return 监听器是否已被移除。
     */
    public static boolean removeBinderDeadListener(@NonNull OnBinderDeadListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return DEAD_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    private static void scheduleBinderDeadListeners() {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnBinderDeadListener> holder : DEAD_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(holder.listener::onBinderDead);
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onBinderDead();
                    } else {
                        MAIN_HANDLER.post(holder.listener::onBinderDead);
                    }
                }

            }
        }
    }

    /**
     * 添加一个用于接收 {@link #requestPermission(int)} 结果的监听器。
     * <p>注意：</p>
     * <ul>
     * <li>监听器将在主线程中调用。</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener) {
        addRequestPermissionResultListener(listener, null);
    }

    /**
     * 添加一个用于接收 {@link #requestPermission(int)} 结果的监听器。
     *
     * @param listener OnBinderReceivedListener
     * @param handler  监听器的调用线程。若为 null，则在主线程中调用。
     */
    public static void addRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener, @Nullable Handler handler) {
        synchronized (RECEIVED_LISTENERS) {
            PERMISSION_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    /**
     * 移除由 {@link #addRequestPermissionResultListener(OnRequestPermissionResultListener)} 添加的监听器。
     *
     * @param listener OnRequestPermissionResultListener
     * @return 监听器是否已被移除。
     */
    public static boolean removeRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return PERMISSION_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    private static void scheduleRequestPermissionResultListener(int requestCode, int result) {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnRequestPermissionResultListener> holder : PERMISSION_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(() -> holder.listener.onRequestPermissionResult(requestCode, result));
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onRequestPermissionResult(requestCode, result);
                    } else {
                        MAIN_HANDLER.post(() -> holder.listener.onRequestPermissionResult(requestCode, result));
                    }
                }
            }
        }
    }

    public static void addLogListener(@NonNull OnLogListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            LOG_LISTENERS.add(new ListenerHolder<>(listener, null));
        }
    }

    public static void removeLogListener(@NonNull OnLogListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            LOG_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    public static void addSentryEventListener(@NonNull OnSentryEventListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            SENTRY_EVENT_LISTENERS.add(new ListenerHolder<>(listener, null));
        }
    }

    public static void removeSentryEventListener(@NonNull OnSentryEventListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            SENTRY_EVENT_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    private static void scheduleSentryEventListener(String eventJson) {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnSentryEventListener> holder : SENTRY_EVENT_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(() -> holder.listener.onSentryEvent(eventJson));
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onSentryEvent(eventJson);
                    } else {
                        MAIN_HANDLER.post(() -> holder.listener.onSentryEvent(eventJson));
                    }
                }
            }
        }
    }

    private static void scheduleLogListener(String appName, String packageName, String action) {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnLogListener> holder : LOG_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(() -> holder.listener.onLog(appName, packageName, action));
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onLog(appName, packageName, action);
                    } else {
                        MAIN_HANDLER.post(() -> holder.listener.onLog(appName, packageName, action));
                    }
                }
            }
        }
    }

    @NonNull
    protected static IShizukuService requireService() {
        if (service == null) {
            throw new IllegalStateException("binder haven't been received");
        }
        return service;
    }

    /**
     * 获取 binder。
     * <p>
     * 普通应用不应使用此方法。
     */
    @Nullable
    public static IBinder getBinder() {
        return binder;
    }

    /**
     * 检查 binder 是否存活。
     * <p>
     * 普通应用应使用监听器，而不是每次都调用此方法。
     *
     * @see #addBinderReceivedListener(OnBinderReceivedListener)
     * @see #addBinderReceivedListenerSticky(OnBinderReceivedListener)
     * @see #addBinderDeadListener(OnBinderDeadListener)
     */
    public static boolean pingBinder() {
        IBinder b = binder;
        return binder != null && binder.pingBinder();
    }

    private static RuntimeException rethrowAsRuntimeException(RemoteException e) {
        return new RuntimeException(e);
    }

    /**
     * 在远程服务上调用 {@link IBinder#transact(int, Parcel, Parcel, int)}。
     * <p>
     * 使用 {@link ShizukuBinderWrapper} 包装原始 binder。
     *
     * @see ShizukuBinderWrapper
     */
    public static void transactRemote(@NonNull Parcel data, @Nullable Parcel reply, int flags) {
        try {
            requireService().asBinder().transact(ShizukuApiConstants.BINDER_TRANSACTION_transact, data, reply, flags);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * 在远程服务上启动一个新进程，参数传递给 {@link Runtime#exec(String, String[], java.io.File)}。
     * <br>从版本 11 起，与 "su" 类似，当调用方进程死亡时该进程将被杀死。如果需求较复杂，
     * 请使用 {@link Shizuku#bindUserService(UserServiceArgs, ServiceConnection)}。
     * <p>
     * 注意，您可能需要在不同线程中读写 RemoteProcess 的流。
     * </p>
     *
     * @return 持有远程进程 binder 的 RemoteProcess
     * @deprecated 此方法仅应在您从 "su" 迁移时使用。
     * binder 调用请使用 {@link Shizuku#transactRemote(Parcel, Parcel, int)}，
     * 复杂需求请使用 {@link Shizuku#bindUserService(UserServiceArgs, ServiceConnection)}。
     * <p>此方法计划在 Shizuku API 14 中移除。
     */
    public static ShizukuRemoteProcess newProcess(@NonNull String[] cmd, @Nullable String[] env, @Nullable String dir) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            data.writeStringArray(cmd);
            data.writeStringArray(env);
            data.writeString(dir);
            // 手写 code 7：服务端 Service.onTransact 的手写分支按原始 AIDL code（2/3/4/7/8…）分发，
            // 而 AIDL 生成的 Proxy 会使用 FIRST_CALL_TRANSACTION + code（即 8），两者错位，
            // 导致 newProcess 返回 null。这里与 attachApplication（手写 17）保持一致，直接使用原始 code 7。
            requireService().asBinder().transact(7, data, reply, 0);
            reply.readException();
            IBinder remote = reply.readStrongBinder();
            if (remote == null) {
                throw new IllegalStateException("Shizuku returned a null remote process for newProcess() — the privileged service could not start the command");
            }
            return new ShizukuRemoteProcess(moe.shizuku.server.IRemoteProcess.Stub.asInterface(remote));
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * 返回远程服务的 uid。
     *
     * @return uid
     * @throws IllegalStateException 若在收到 binder 之前调用
     */
    public static int getUid() {
        if (serverUid != -1) return serverUid;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            // 手写 code 3，匹配服务端 Service.onTransact 的手写 case 3（getUid），
            // 避免 AIDL 生成的 Proxy（FIRST_CALL_TRANSACTION + 3 = 4）与服务端手写分支错位。
            requireService().asBinder().transact(3, data, reply, 0);
            reply.readException();
            serverUid = reply.readInt();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        } catch (SecurityException e) {
            // Shizuku pre-v11 且未授予权限
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
        return serverUid;
    }

    /**
     * 返回远程服务版本。
     *
     * @return 服务器版本
     */
    public static int getVersion() {
        if (serverApiVersion != -1) return serverApiVersion;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            // 手写 code 2，匹配服务端 Service.onTransact 的手写 case 2（getVersion）。
            requireService().asBinder().transact(2, data, reply, 0);
            reply.readException();
            serverApiVersion = reply.readInt();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        } catch (SecurityException e) {
            // Shizuku pre-v11 且未授予权限
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
        return serverApiVersion;
    }

    /**
     * 返回远程服务版本是否低于 11。
     *
     * @return 远程服务版本是否低于 11
     */
    public static boolean isPreV11() {
        return preV11;
    }

    /**
     * 返回本库发布时最新的服务版本。
     *
     * @return 最新服务版本
     * @see Shizuku#getVersion()
     */
    public static int getLatestServiceVersion() {
        return ShizukuApiConstants.SERVER_VERSION;
    }

    /**
     * 返回 Shizuku 服务器进程的 SELinux 上下文。
     *
     * <p>对于 adb，上下文应始终为 <code>u:r:shell:s0</code>。
     * <br>对于 root，上下文取决于用户使用的 su。例如，Magisk 的上下文为 <code>u:r:magisk:s0</code>。
     * 如果用户的 su 不允许 su 与应用之间的 binder 调用，Shizuku 将切换到 <code>u:r:shell:s0</code> 上下文。
     * </p>
     *
     * @return SELinux 上下文
     * @since 自版本 6 添加
     */
    public static String getSELinuxContext() {
        if (serverContext != null) return serverContext;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            // 手写 code 8，匹配服务端 Service.onTransact 的手写 case 8（getSELinuxContext）。
            requireService().asBinder().transact(8, data, reply, 0);
            reply.readException();
            serverContext = reply.readString();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        } catch (SecurityException e) {
            // Shizuku pre-v11 且未授予权限
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
        return serverContext;
    }

    public static class UserServiceArgs {

        final ComponentName componentName;
        int versionCode = 1;
        String processName;
        String tag;
        boolean debuggable = false;
        boolean daemon = true;
        boolean use32BitAppProcess = false;

        public UserServiceArgs(@NonNull ComponentName componentName) {
            this.componentName = componentName;
        }

        /**
         * daemon 控制服务是否以守护模式运行。
         * <br>在非守护模式下，当应用进程死亡时服务将停止。
         * <br>在守护模式下，服务将一直运行，直到调用 {@link Shizuku#unbindUserService(UserServiceArgs, ServiceConnection, boolean)}。
         * <p>出于向上兼容的原因，{@code daemon} 默认为 {@code true}。
         *
         * @param daemon 是否守护
         */
        public UserServiceArgs daemon(boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        /**
         * tag 用于区分不同的服务。
         * <p>如果您想混淆用户服务类，则需要设置一个稳定的 tag。
         * <p>默认情况下，同一包在所有用户中共享同一个用户服务。
         *
         * @param tag 标签
         */
        public UserServiceArgs tag(@NonNull String tag) {
            this.tag = tag;
            return this;
        }

        /**
         * versionCode 用于区分不同的服务。
         * <p>当服务代码更新时，使用不同的 versionCode，这样 Shizuku 或 Sui 服务器
         * 才能为您重新创建用户服务。
         *
         * @param versionCode 版本号
         */
        public UserServiceArgs version(int versionCode) {
            this.versionCode = versionCode;
            return this;
        }

        /**
         * 设置服务是否可调试。启用“显示所有进程”时，可以在进程列表中找到该进程。
         *
         * @param debuggable 是否可调试
         */
        public UserServiceArgs debuggable(boolean debuggable) {
            this.debuggable = debuggable;
            return this;
        }

        /**
         * 设置用户服务进程的名称后缀。最终进程名形如
         * <code>com.example:suffix</code>。
         *
         * @param processNameSuffix 名称后缀
         */
        public UserServiceArgs processNameSuffix(String processNameSuffix) {
            this.processName = processNameSuffix;
            return this;
        }

        /**
         * 设置在 64 位设备上是否使用 32 位 app_process。
         * <p>此方法在仅 64 位设备上无效。
         * <p>除非有特殊需求，否则您绝不应使用此方法。
         * <p><strong>原因：</strong>
         * <p><a href="https://developer.android.com/distribute/best-practices/develop/64-bit">Google 自 2019 年 8 月起要求所有提交到 Google Play 的应用为 64 位。</a>
         * <p><a href="https://www.arm.com/blogs/blueprint/64-bit">ARM 宣布自 2023 年起所有 Arm Cortex-A CPU 移动核心将仅支持 64 位。</a>
         *
         * @param use32BitAppProcess 是否使用 32 位 app_process
         */
        private UserServiceArgs use32BitAppProcess(boolean use32BitAppProcess) {
            this.use32BitAppProcess = use32BitAppProcess;
            return this;
        }

        private Bundle forAdd() {
            Bundle options = new Bundle();
            options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, componentName);
            options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE, debuggable);
            options.putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, versionCode);
            options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, daemon);
            options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, use32BitAppProcess);
            options.putString(ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME,
                    Objects.requireNonNull(processName, "process name suffix must not be null"));
            if (tag != null) {
                options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, tag);
            }
            return options;
        }

        private Bundle forRemove(boolean remove) {
            Bundle options = new Bundle();
            options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, componentName);
            if (tag != null) {
                options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, tag);
            }
            options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_REMOVE, remove);
            return options;
        }
    }

    /**
     * 用户服务（User Service）类似于
     * <a href="https://developer.android.com/guide/components/bound-services">绑定服务（Bound Services）</a>。
     * 区别在于，该服务运行在另一个进程中，并以 root（UID 0）或 shell
     * （UID 2000，若后端为 Shizuku 且用户通过 adb 启动 Shizuku）的身份（Linux UID）运行。
     * <p>
     * 用户服务可以“守护模式”运行。
     * 在“守护模式”（默认行为）下，服务将一直运行，直到您调用 “unbind” 方法。
     * 在“非守护模式”下，当调用 “bind” 方法的进程死亡时，服务将被停止。
     * <p>
     * 调用 “unbind” 方法时，用户服务将不会被杀死。
     * 您需要在服务中实现一个 “destroy” 方法。该方法的交易代码为 {@code 16777115}
     * （在 aidl 中使用 {@code 16777114}）。在该方法中，您可以执行一些清理工作，
     * 并在最后调用 {@link System#exit(int)}。
     * <p>
     * 如果后端是 Shizuku，无论是否为守护模式，当 Shizuku 服务停止或重启时，
     * 用户服务都将被杀死。Shizuku 会将 binder 发送给所有 Shizuku 应用。
     * 因此，您只需重新启动用户服务即可。
     * <p>
     * <b>在用户服务中使用 Android API：</b>
     * <p>
     * 用户服务进程中的非 SDK API 没有限制。但是，它不是一个合法的 Android
     * 应用进程。因此，即使您可以获取 {@code Context} 实例，许多 API（例如
     * {@code Context#registerReceiver} 和 {@code Context#getContentResolver}）
     * 也无法工作。您需要深入研究 Android 源码以了解其工作原理，
     * 这样您才能安全而优雅地实现您的服务。
     * <p>
     * 请注意，要让 UserService 使用最新代码，应在 Android Studio 中勾选
     * “运行/调试配置”-“始终使用包管理器安装”。
     *
     * @see UserServiceArgs
     * @since 自版本 10 添加
     */
    public static void bindUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn) {
        ShizukuServiceConnection connection = ShizukuServiceConnections.get(args);
        connection.addConnection(conn);
        try {
            requireService().addUserService(connection, args.forAdd());
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * 与 {@link Shizuku#bindUserService(UserServiceArgs, ServiceConnection)} 类似，
     * 但若用户服务未运行，则不启动它。
     *
     * @return 若服务正在运行则返回服务版本；若服务未运行则返回 -1。
     * 对于 Shizuku pre-v13，若服务正在运行，版本始终为 0。
     * @see Shizuku#bindUserService(UserServiceArgs, ServiceConnection)
     * @since 自版本 12 添加
     */
    public static int peekUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn) {
        ShizukuServiceConnection connection = ShizukuServiceConnections.get(args);
        connection.addConnection(conn);
        int result;
        try {
            Bundle bundle = args.forAdd();
            bundle.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, true);
            result = requireService().addUserService(connection, bundle);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }

        boolean atLeast13 = !Shizuku.isPreV11() && Shizuku.getVersion() >= 13;
        if (atLeast13) {
            return result;
        }

        // 在 pre-13 上，0 表示正在运行
        if (result == 0) {
            return 0;
        }
        // 其余均表示未运行
        return -1;
    }

    /**
     * 移除用户服务。
     * <p>
     * 您需要在服务中实现一个 “destroy” 方法，
     * 否则服务将不会被杀死。
     *
     * @param remove 是否移除（杀死）远程用户服务。
     * @see Shizuku#bindUserService(UserServiceArgs, ServiceConnection)
     */
    public static void unbindUserService(@NonNull UserServiceArgs args, @Nullable ServiceConnection conn, boolean remove) {
        if (remove) {
            try {
                requireService().removeUserService(null /* (unused) */, args.forRemove(true));
            } catch (RemoteException e) {
                throw rethrowAsRuntimeException(e);
            }
        } else {
            /*
             * 当以 remove=false 调用 unbindUserService 时，虽然 ShizukuServiceConnection
             * 实例已从 ShizukuServiceConnections 中移除，但它仍然存在（因为它是一个 Binder），
             * 并且仍会从服务接收 “connected”“died”，然后调用其 ServiceConnection connections[]
             * 的回调。
             * 这最终会导致在之后调用 bindUserService 后，ServiceConnection#onServiceConnected/
             * onServiceDisconnected 被多次调用，这并非预期行为。
             */

            ShizukuServiceConnection connection = ShizukuServiceConnections.get(args);

            /*
             * 对于较新版本的服务器，我们可以直接以 remove=false 调用 removeUserService。
             * 这不会杀死服务，但会将 ShizukuServiceConnection 实例从服务器移除。
             */
            if (Shizuku.getVersion() >= 14 || Shizuku.getVersion() == 13 && Shizuku.getServerPatchVersion() >= 4) {
                try {
                    requireService().removeUserService(connection, args.forRemove(false));
                } catch (RemoteException e) {
                    throw rethrowAsRuntimeException(e);
                }
            }

            /*
             * 作为旧版本服务器的解决方案，我们可以在此处清空 connections[]。
             */
            connection.clearConnections();
            ShizukuServiceConnections.remove(connection);
        }
    }

    /**
     * 检查远程服务是否具有特定权限。
     *
     * @param permission 权限名称
     * @return PackageManager.PERMISSION_DENIED 或 PackageManager.PERMISSION_GRANTED
     */
    public static int checkRemotePermission(String permission) {
        if (serverUid == 0) return PackageManager.PERMISSION_GRANTED;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            data.writeString(permission);
            // 手写 code 4，匹配服务端 Service.onTransact 的手写 case 4（checkPermission）。
            requireService().asBinder().transact(4, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * 请求权限。
     * <p>
     * 与运行时权限不同，您需要添加一个监听器来接收结果。
     *
     * @param requestCode 应用特定的请求码，用于与
     *                    {@link OnRequestPermissionResultListener#onRequestPermissionResult(int, int)} 报告的结果匹配。
     * @see #addRequestPermissionResultListener(OnRequestPermissionResultListener)
     * @see #removeRequestPermissionResultListener(OnRequestPermissionResultListener)
     * @since 自版本 11 添加
     */
    public static void requestPermission(int requestCode) {
        try {
            requireService().requestPermission(requestCode);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * 检查自身是否具有权限。
     *
     * @return {@link android.content.pm.PackageManager#PERMISSION_GRANTED}
     * 或 {@link android.content.pm.PackageManager#PERMISSION_DENIED}。
     * @since 自版本 11 添加
     */
    public static int checkSelfPermission() {
        if (permissionGranted) return PackageManager.PERMISSION_GRANTED;
        try {
            permissionGranted = requireService().checkSelfPermission();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return permissionGranted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    /**
     * 在请求权限之前是否应显示带有理由的 UI。
     *
     * @since 自版本 11 添加
     */
    public static boolean shouldShowRequestPermissionRationale() {
        if (permissionGranted) return false;
        if (shouldShowRequestPermissionRationale) return true;
        try {
            shouldShowRequestPermissionRationale = requireService().shouldShowRequestPermissionRationale();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return shouldShowRequestPermissionRationale;
    }

    // --------------------- 非应用端调用 ----------------------

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void exit() {
        try {
            requireService().exit();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void attachUserService(@NonNull IBinder binder, @NonNull Bundle options) {
        try {
            requireService().attachUserService(binder, options);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, @NonNull Bundle data) {
        try {
            requireService().dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static int getFlagsForUid(int uid, int mask) {
        try {
            return requireService().getFlagsForUid(uid, mask);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void updateFlagsForUid(int uid, int mask, int value) {
        try {
            requireService().updateFlagsForUid(uid, mask, value);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static int getServerPatchVersion() {
        return serverPatchVersion;
    }

    /**
     * 检查服务器上是否启用了 ShizukuX 增强 API。
     *
     * @return 若已启用则返回 true
     */
    public static boolean isCustomApiEnabled() {
        if (!pingBinder()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            // 10002 = BINDER_TRANSACTION_isCustomApiEnabled
            if (binder.transact(10002, data, reply, 0)) {
                reply.readException();
                return reply.readInt() != 0;
            }
        } catch (RemoteException e) {
            // 不是 ShizukuX 服务器或事务失败
        } finally {
            reply.recycle();
            data.recycle();
        }
        return false;
    }

    /**
     * 通过 Shizuku 服务器支持 Dhizuku 兼容性的内部类。
     */
    public static class Dhizuku {
        @Nullable
        public static IBinder getBinder() {
            if (!pingBinder()) return null;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
                // 10003 = BINDER_TRANSACTION_getDhizukuBinder
                if (binder.transact(10003, data, reply, 0)) {
                    reply.readException();
                    return reply.readStrongBinder();
                }
            } catch (RemoteException e) {
                // 不是 ShizukuX 服务器或事务失败
            } finally {
                reply.recycle();
                data.recycle();
            }
            return null;
        }
    }
}
