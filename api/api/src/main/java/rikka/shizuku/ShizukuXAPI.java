package rikka.shizuku;

import android.os.IBinder;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IShizukuService;

/**
 * ShizukuX API — core helpers available when the connected Shizuku server
 * is a ShizukuX build.
 *
 * <p>All methods that touch a remote binder are safe to call from any thread.
 * They return {@code null}/{@code false}/empty-list when Shizuku is not
 * connected or a transient IPC error occurs.
 */
public class ShizukuXAPI {
    private static final String TAG = "ShizukuX API";

    /** Timeout for blocking shell-command reads, in seconds. */
    private static final long SHELL_TIMEOUT_SECONDS = 30;

    // -------------------------------------------------------------------------
    // Core connection helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the connected server is a ShizukuX build that
     * has the enhanced API enabled. Safe to call from any thread.
     */
    public static boolean isEnhancedApiSupported() {
        return Shizuku.isCustomApiEnabled();
    }

    /**
     * Returns a live {@link IShizukuService} proxy, or {@code null} if
     * Shizuku is not connected or the binder has died.
     */
    @Nullable
    private static IShizukuService getShizukuService() {
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null || !binder.isBinderAlive()) return null;
            return IShizukuService.Stub.asInterface(binder);
        } catch (Exception e) {
            Log.w(TAG, "getShizukuService: failed to obtain binder", e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Shell
    // -------------------------------------------------------------------------

    /** Result of a synchronous shell command execution. */
    public static class CommandResult {
        public final int exitCode;
        @NonNull public final String output;
        @NonNull public final String error;

        public CommandResult(int exitCode, @NonNull String output, @NonNull String error) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }

        public boolean isSuccess() { return exitCode == 0; }
    }

    /**
     * Execute a shell command string (via {@code sh -c}) through Shizuku and
     * return the result synchronously. Blocks the calling thread for up to
     * {@link #SHELL_TIMEOUT_SECONDS} seconds before returning an error result.
     *
     * <p>Do not call on the main thread.
     */
    @NonNull
    public static CommandResult executeShell(@NonNull String command) {
        return executeShell(new String[]{"sh", "-c", command});
    }

    /**
     * Execute an argument array through Shizuku and return the result
     * synchronously. Blocks up to {@link #SHELL_TIMEOUT_SECONDS}.
     *
     * <p>Do not call on the main thread.
     */
    @NonNull
    public static CommandResult executeShell(@NonNull String[] cmd) {
        try {
            // newProcess is the correct public API surface for Shizuku shell execution.
            ShizukuRemoteProcess process = Shizuku.newProcess(cmd, null, null);
            if (process == null) {
                return new CommandResult(-1, "", "Process creation returned null");
            }

            final StringBuilder output = new StringBuilder();
            final StringBuilder error  = new StringBuilder();

            // Drain stderr on a parallel thread: if stdout fills the OS pipe
            // buffer while we block reading it, stderr must drain or we deadlock.
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        error.append(line).append('\n');
                    }
                } catch (Exception ignored) {}
            }, "shizuku-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            stderrThread.join(TimeUnit.SECONDS.toMillis(SHELL_TIMEOUT_SECONDS));
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, output.toString().trim(), error.toString().trim());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", "Interrupted");
        } catch (Exception e) {
            return new CommandResult(-1, "", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    /** Wrappers for Android System settings (system / secure / global). */
    public static class Settings {

        public static boolean putSystem(@NonNull String key, @NonNull String value) {
            return executeShell(new String[]{"settings", "put", "system", key, value}).isSuccess();
        }

        public static boolean putSecure(@NonNull String key, @NonNull String value) {
            return executeShell(new String[]{"settings", "put", "secure", key, value}).isSuccess();
        }

        public static boolean putGlobal(@NonNull String key, @NonNull String value) {
            return executeShell(new String[]{"settings", "put", "global", key, value}).isSuccess();
        }

        @NonNull
        public static String getSystem(@NonNull String key) {
            return executeShell(new String[]{"settings", "get", "system", key}).output;
        }

        @NonNull
        public static String getSecure(@NonNull String key) {
            return executeShell(new String[]{"settings", "get", "secure", key}).output;
        }

        @NonNull
        public static String getGlobal(@NonNull String key) {
            return executeShell(new String[]{"settings", "get", "global", key}).output;
        }
    }

    // -------------------------------------------------------------------------
    // Package Manager
    // -------------------------------------------------------------------------

    /** Wrappers for package-manager operations via Shizuku. */
    public static class PackageManager {

        public static boolean installPackage(@NonNull String apkFilePath) {
            return executeShell(new String[]{"pm", "install", "-r", apkFilePath}).isSuccess();
        }

        public static boolean uninstallPackage(@NonNull String packageName) {
            return executeShell(new String[]{"pm", "uninstall", packageName}).isSuccess();
        }

        public static boolean clearPackageData(@NonNull String packageName) {
            return executeShell(new String[]{"pm", "clear", packageName}).isSuccess();
        }
    }
}
