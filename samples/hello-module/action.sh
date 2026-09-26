#!/system/bin/sh
# action.sh —— 用户在模块详情页点击「运行」时手动执行（超时 60s）。
# 演示：打印环境变量 + 当前身份。
echo "Hello from ReShizukuX module!"
id
echo "MODULE_DIR=$MODULE_DIR"
echo "ANDROID_SDK=$ANDROID_SDK"
echo "MODULE_ID=$MODULE_ID"
echo "MODULE_VERSION=$MODULE_VERSION"
