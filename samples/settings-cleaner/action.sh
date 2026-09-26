#!/system/bin/sh
# action.sh —— 手动执行（超时 60s）。
# 把三项动画缩放设为 0.5x，演示运行时修改全局设置。
echo "清理中..."
settings put global window_animation_scale 0.5
settings put global transition_animation_scale 0.5
settings put global animator_duration_scale 0.5
echo "动画缩放已设为 0.5x"
echo "当前值:"
settings get global window_animation_scale
