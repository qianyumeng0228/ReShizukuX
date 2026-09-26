#!/system/bin/sh
# customize.sh —— 安装时执行（超时 120s）。
# 把当前动画缩放值记录到模块目录下的 backup.txt，供 uninstall.sh 回滚。
BACKUP="$MODULE_DIR/backup.txt"

: > "$BACKUP"
for key in window_animation_scale transition_animation_scale animator_duration_scale; do
    value=$(settings get global "$key")
    echo "$key=$value" >> "$BACKUP"
done

echo "已备份当前设置到 $BACKUP："
cat "$BACKUP"
