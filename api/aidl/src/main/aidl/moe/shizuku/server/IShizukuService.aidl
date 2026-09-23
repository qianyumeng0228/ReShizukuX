package moe.shizuku.server;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuServiceConnection;
import af.shizuku.server.IVirtualMachineManager;
import af.shizuku.server.IStorageProxy;
import af.shizuku.server.IAICoreExtra;
import af.shizuku.server.IAIAutomationBridge;
import af.shizuku.server.IWindowManagerExtra;
import af.shizuku.server.IContinuityBridge;
import af.shizuku.server.IOverlayManagerExtra;
import af.shizuku.server.INetworkGovernorExtra;
import af.shizuku.server.IActivityManagerExtra;
import af.shizuku.server.IStatusBarGovernorExtra;
import af.shizuku.server.IPackageGovernorExtra;
import af.shizuku.server.IDisplayTunerExtra;
import af.shizuku.server.IAppInspector;
import af.shizuku.server.IPrivilegedDataSource;
import af.shizuku.server.IBackupRestoreExtra;
import af.shizuku.server.IApkPatcher;

interface IShizukuService {

    int getVersion() = 2;

    int getUid() = 3;

    int checkPermission(String permission) = 4;

    IRemoteProcess newProcess(in String[] cmd, in String[] env, in String dir) = 7;

    String getSELinuxContext() = 8;

    String getSystemProperty(in String name, in String defaultValue) = 9;

    void setSystemProperty(in String name, in String value) = 10;

    int addUserService(in IShizukuServiceConnection conn, in Bundle args) = 11;

    int removeUserService(in IShizukuServiceConnection conn, in Bundle args) = 12;

    void requestPermission(int requestCode) = 14;

    boolean checkSelfPermission() = 15;

    boolean shouldShowRequestPermissionRationale() = 16;

    void attachApplication(in IShizukuApplication application,in Bundle args) = 17;

    void exit() = 100;

    void attachUserService(in IBinder binder, in Bundle options) = 101;

    oneway void dispatchPackageChanged(in Intent intent) = 102;

    boolean isHidden(int uid) = 103;

    oneway void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, in Bundle data) = 104;

    int getFlagsForUid(int uid, int mask) = 105;

    void updateFlagsForUid(int uid, int mask, int value) = 106;

    IVirtualMachineManager getVirtualMachineManager() = 107;

    IStorageProxy getStorageProxy() = 108;

    IAICoreExtra getAICoreExtra() = 109;

    IWindowManagerExtra getWindowManagerExtra() = 110;

    IContinuityBridge getContinuityBridge() = 111;

    void updateExtraFeatureEnabled(String key, boolean enabled) = 112;

    void setExtraSetting(String key, String value) = 116;

    IOverlayManagerExtra getOverlayManagerExtra() = 113;

    INetworkGovernorExtra getNetworkGovernorExtra() = 114;

    IActivityManagerExtra getActivityManagerExtra() = 115;

    void elevateApp(String packageName) = 117;

    List<String> getRecentLogs() = 118;

    String getExtraSetting(String key) = 119;

    boolean isExtraFeatureEnabled(String key) = 120;

    void registerAIAutomationBridge(in IAIAutomationBridge bridge) = 121;

    IStatusBarGovernorExtra getStatusBarGovernorExtra() = 122;

    IPackageGovernorExtra getPackageGovernorExtra() = 123;

    IDisplayTunerExtra getDisplayTunerExtra() = 124;

    IAppInspector getAppInspector() = 125;

    IPrivilegedDataSource getPrivilegedDataSource() = 126;

    IBackupRestoreExtra getBackupRestoreExtra() = 127;

    IApkPatcher getApkPatcher() = 128;
 }
