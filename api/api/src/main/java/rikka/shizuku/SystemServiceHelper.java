package rikka.shizuku;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@SuppressLint("PrivateApi")
public class SystemServiceHelper {

    private static final Map<String, IBinder> SYSTEM_SERVICE_CACHE = new HashMap<>();
    private static final Map<String, Integer> TRANSACT_CODE_CACHE = new HashMap<>();

    private static Method getService;

    static {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            getService = sm.getMethod("getService", String.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Log.w("SystemServiceHelper", Log.getStackTraceString(e));
        }
    }

    /**
     * 返回具有给定名称的服务引用。
     *
     * @param name 要获取的服务名称，例如 android.content.pm.IPackageManager 对应的 "package"
     * @return 服务的引用；若服务不存在则返回 <code>null</code>
     */
    public static IBinder getSystemService(@NonNull String name) {
        IBinder binder = SYSTEM_SERVICE_CACHE.get(name);
        // 缓存的 binder 可能比其指向的服务存活更久（宿主进程重启后 ServiceManager
        // 会下发新实例，或进程直接死亡）——否则由于 map 查找不会重新调用 getService，
        // 一个已失效的条目将被永久返回。
        if (binder != null && !binder.isBinderAlive()) {
            SYSTEM_SERVICE_CACHE.remove(name);
            binder = null;
        }
        if (binder == null) {
            try {
                binder = (IBinder) getService.invoke(null, name);
            } catch (IllegalAccessException | InvocationTargetException e) {
                Log.w("SystemServiceHelper", Log.getStackTraceString(e));
            }
            if (binder != null) {
                SYSTEM_SERVICE_CACHE.put(name, binder);
            }
        }
        return binder;
    }

    /**
     * 从给定的类和方法名返回事务代码（transaction code）。
     *
     * @param className  类名，例如 "android.content.pm.IPackageManager$Stub"
     * @param methodName 方法名，例如 "getInstalledPackages"
     * @return 事务代码；若类或方法不存在则返回 <code>null</code>
     * @deprecated 请改用 {@link ShizukuBinderWrapper}
     */
    @Deprecated
    public static Integer getTransactionCode(@NonNull String className, @NonNull String methodName) {
        final String fieldName = "TRANSACTION_" + methodName;
        final String key = className + "." + fieldName;

        Integer value = TRANSACT_CODE_CACHE.get(key);
        if (value != null) return value;

        try {
            final Class<?> cls = Class.forName(className);
            Field declaredField = null;
            try {
                declaredField = cls.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != int.class)
                        continue;

                    String name = f.getName();
                    if (name.startsWith(fieldName + "_")
                            && TextUtils.isDigitsOnly(name.substring(fieldName.length() + 1))) {
                        declaredField = f;
                        break;
                    }
                }
            }
            if (declaredField == null) {
                return null;
            }

            declaredField.setAccessible(true);
            value = declaredField.getInt(cls);

            TRANSACT_CODE_CACHE.put(key, value);
            return value;
        } catch (ClassNotFoundException | IllegalAccessException e) {
            android.util.Log.e("SystemServiceHelper", "Failed to get transaction code", e);
        }
        return null;
    }

    /**
     * 为 {@link Shizuku#transactRemote(Parcel, Parcel, int)} 获取一个新的数据 parcel。
     *
     * @param serviceName   系统服务名称
     * @param interfaceName 用于反射的接口名
     * @param methodName    用于反射的方法名
     * @return 数据 parcel
     * @throws NullPointerException 无法获取系统服务或事务代码
     * @deprecated 请改用 {@link ShizukuBinderWrapper}
     */
    @Deprecated
    public static Parcel obtainParcel(@NonNull String serviceName, @NonNull String interfaceName, @NonNull String methodName) {
        return obtainParcel(serviceName, interfaceName, interfaceName + "$Stub", methodName);
    }

    /**
     * 为 {@link Shizuku#transactRemote(Parcel, Parcel, int)} 获取一个新的数据 parcel。
     *
     * @param serviceName   系统服务名称
     * @param interfaceName 接口名
     * @param className     用于反射的类名
     * @param methodName    用于反射的方法名
     * @return 数据 parcel
     * @throws NullPointerException 无法获取系统服务或事务代码
     * @deprecated 请改用 {@link ShizukuBinderWrapper}
     */
    @Deprecated
    public static Parcel obtainParcel(@NonNull String serviceName, @NonNull String interfaceName, @NonNull String className, @NonNull String methodName) {
        throw new UnsupportedOperationException("Direct use of Shizuku#transactRemote is no longer supported, please use ShizukuBinderWrapper");
    }
}
