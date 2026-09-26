#!/system/bin/sh
# uninstall.sh —— 卸载前执行（超时 60s，失败不阻塞卸载）。
# 从 customize.sh 生成的 backup.txt 恢复原始设置值。
BACKUP="$MODULE_DIR/backup.txt"

if [ -f "$BACKUP" ]; then
    while IFS='=' read -r key value; do
        [ -z "$key" ] && continue
        # 备份时若系统为空值，settings get 返回 "null"，跳过恢复
        if [ "$value" != "null" ]; then
            settings put global "$key" "$value"
            echo "已恢复 $key=$value"
        else
            echo "跳过 $key（原值为空/null）"
        fi
    done < "$BACKUP"
    echo "已恢复原始设置"
else
    echo "未找到 backup.txt，无需恢复"
fi
