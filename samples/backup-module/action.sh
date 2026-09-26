#!/system/bin/sh
# action.sh —— 手动触发一次立即备份（超时 60s）。
echo "立即备份..."
mkdir -p /sdcard/ReShizukuBackup
pm list packages > /sdcard/ReShizukuBackup/backup-$(date +%Y%m%d-%H%M%S).txt
echo "完成，文件列表："
ls -la /sdcard/ReShizukuBackup/
