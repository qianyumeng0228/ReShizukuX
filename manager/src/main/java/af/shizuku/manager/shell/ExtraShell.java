package af.shizuku.manager.shell;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

import moe.shizuku.server.IShizukuService;
import rikka.rish.RishConfig;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;
import af.shizuku.manager.utils.Logger;
import timber.log.Timber;

public class ExtraShell {

    private static final Logger LOGGER = new Logger("ExtraShell");

    private static class LogTree extends Timber.Tree {
        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            if (priority >= Log.ERROR) {
                System.err.println(message);
                if (t != null) t.printStackTrace(System.err);
            } else {
                System.out.println(message);
            }
        }
    }

    private static void printHelp() {
        LOGGER.i("ReShizukuX CLI Helper (plus)");
        LOGGER.w("Usage: plus [command] [args]");
        LOGGER.i("");
        LOGGER.i("Commands:");
        LOGGER.i("  su [command]              Run command via SU Bridge");
        LOGGER.i("  reboot [recovery|download] Reboot to specialized modes");
        LOGGER.i("  appops [pkg]              Elevate permissions for package");
        LOGGER.i("  log                       View the privileged activity log (server-side)");
        LOGGER.i("  doctor                    Run system diagnostics");
        LOGGER.i("  spoof                     View current device identity spoofing");
        LOGGER.i("  help                      Show this help message");
    }

    private static void handleSu(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            LOGGER.w("Usage: plus su [command]");
            return;
        }

        String[] fullCmd = new String[args.length];
        fullCmd[0] = "su";
        System.arraycopy(args, 1, fullCmd, 1, args.length - 1);

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        // This will be intercepted by ShizukuService.newProcess
        service.newProcess(fullCmd, null, null);
        LOGGER.i("Command sent to SU Bridge.");
    }

    private static void handleAppOps(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            LOGGER.w("Usage: plus appops [package_name]");
            return;
        }

        String packageName = args[1];
        LOGGER.i("Requesting permission elevation for: " + packageName);

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        service.elevateApp(packageName);
        LOGGER.i("Elevation request sent to server.");
    }

    private static void handleReboot(String[] args) {
        String mode = args.length > 1 ? args[1] : "";
        try {
            String[] cmd = mode.isEmpty() ? new String[]{"reboot"} : new String[]{"reboot", mode};
            LOGGER.i("Rebooting to " + (mode.isEmpty() ? "system" : mode) + "...");
            Runtime.getRuntime().exec(cmd).waitFor();
        } catch (Exception e) {
            LOGGER.e(e, "Reboot failed");
        }
    }

    private static void handleLog(IBinder binder) throws RemoteException {
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        List<String> logs = service.getRecentLogs();
        if (logs == null || logs.isEmpty()) {
            LOGGER.i("No recent privileged activities recorded in server buffer.");
        } else {
            LOGGER.i("Recent Privileged Activities (Server-side Buffer):");
            for (String log : logs) {
                LOGGER.i(log);
            }
        }
    }

    private static void handleSpoof(IBinder binder) throws RemoteException {
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        boolean enabled = service.isExtraFeatureEnabled("spoof_device");
        String target = service.getExtraSetting("spoof_target");

        LOGGER.i("Identity Spoofing: " + (enabled ? "ACTIVE" : "DISABLED"));
        if (enabled) {
            LOGGER.i("Current Target: " + (target != null ? target : "None (Default)"));
        }
        LOGGER.i("Note: Spoof targets are managed via ReShizukuX Settings > Root Compatibility.");
    }

    private static void handleDoctor(IBinder binder) throws RemoteException {
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        LOGGER.i("ReShizukuX System Doctor Diagnostics");
        LOGGER.i("==================================");
        LOGGER.i("Server Version: " + service.getVersion());
        LOGGER.i("Server UID: " + service.getUid());
        LOGGER.i("SELinux Context: " + service.getSELinuxContext());

        LOGGER.i("\nPlus Features Status:");
        String[] features = {"su_bridge", "shell_interceptor"};
        for (String f : features) {
            LOGGER.i("  %-18s: %s", f, service.isExtraFeatureEnabled(f) ? "ENABLED" : "DISABLED");
        }

        LOGGER.i("\nDevice Identity:");
        LOGGER.i("  Model: " + android.os.Build.MODEL);
        LOGGER.i("  Brand: " + android.os.Build.BRAND);
        LOGGER.i("  SDK: " + android.os.Build.VERSION.SDK_INT);
    }

    public static void main(String[] args, String packageName, IBinder binder, Handler handler) {
        Timber.plant(new LogTree());
        if (args.length == 0 || args[0].equals("help")) {
            printHelp();
            System.exit(0);
        }

        Shizuku.onBinderReceived(binder, packageName);

        try {
            switch (args[0]) {
                case "log":
                    handleLog(binder);
                    break;
                case "su":
                    handleSu(args, binder);
                    break;
                case "reboot":
                    handleReboot(args);
                    break;
                case "appops":
                    handleAppOps(args, binder);
                    break;
                case "spoof":
                    handleSpoof(binder);
                    break;
                case "doctor":
                    handleDoctor(binder);
                    break;
                default:
                    LOGGER.w("Unknown command: " + args[0]);
                    printHelp();
            }
        } catch (Throwable tr) {
            LOGGER.e(tr, "Uncaught exception in ExtraShell");
        } finally {
            System.exit(0);
        }
    }
}
