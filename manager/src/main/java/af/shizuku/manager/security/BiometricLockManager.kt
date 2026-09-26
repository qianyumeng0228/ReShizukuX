package af.shizuku.manager.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 生物识别锁管理器（ReShizukuX beta1 组B）。
 *
 *  - 单例，SharedPreferences 名 "biometric_lock_prefs"，键 "biometric_lock_enabled"。
 *  - 支持的验证器：BIOMETRIC_WEAK or DEVICE_CREDENTIAL（指纹 / 面容 / 设备凭据 PIN 图案密码）。
 *  - [authenticate] 需要 [FragmentActivity]（androidx.biometric 1.1.0 约束）。
 */
class BiometricLockManager private constructor() {

    private val prefsName = "biometric_lock_prefs"
    private val keyEnabled = "biometric_lock_enabled"

    /** 设备是否支持生物识别或设备凭据解锁。 */
    fun isAvailable(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        val canBiometric = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        val canCredential = bm.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        return canBiometric == BiometricManager.BIOMETRIC_SUCCESS ||
            canCredential == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** 已配置的可用验证方式描述（指纹 / 面容 / 设备凭据）。 */
    fun availableMethods(context: Context): List<String> {
        val bm = BiometricManager.from(context)
        val methods = mutableListOf<String>()
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) {
            methods += "指纹 / 面容"
        }
        if (bm.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS) {
            methods += "设备凭据（PIN / 图案 / 密码）"
        }
        return methods
    }

    fun isLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(keyEnabled, false)

    fun setLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(keyEnabled, enabled).apply()
    }

    /** 弹出系统 BiometricPrompt。 */
    fun authenticate(activity: FragmentActivity, callback: Callback) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    callback.onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // ERROR_USER_CANCELED / ERROR_NEGATIVE_BUTTON 视为取消。
                    callback.onError(errorCode, errString)
                }

                override fun onAuthenticationFailed() {
                    callback.onFailed()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("生物识别验证")
            .setSubtitle("验证以继续使用 ReShizukuX")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    interface Callback {
        fun onSuccess()
        fun onError(errorCode: Int, errString: CharSequence)
        fun onFailed()
    }

    companion object {
        @Volatile
        private var INSTANCE: BiometricLockManager? = null

        fun getInstance(): BiometricLockManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BiometricLockManager().also { INSTANCE = it }
            }
    }
}
