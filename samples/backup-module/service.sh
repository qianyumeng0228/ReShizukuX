#!/system/bin/sh
# service.sh —— 后台常驻脚本（用户在模块详情页手动启用「后台运行」后由 watchdog 拉起）。
# 以 shell uid=2000 运行，不需要 root；pm list packages 在 shell 权限下可用。
while true; do
    mkdir -p /sdcard/ReShizukuBackup
    echo "[$(date)] 备份检查中..."
    pm list packages > /sdcard/ReShizukuBackup/packages-$(date +%Y%m%d-%H%M%S).txt 2>/dev/null
    echo "[$(date)] 已备份应用列表"
    sleep 3600
done
