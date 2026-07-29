# 工时记录助手

一款本地优先的 Android 工时记录应用，通过前台定位服务识别到达公司、离开公司和回家事件，并生成每日与月度工时记录。

## 主要功能

- 公司和家庭地理围栏
- 白班、夜班及跨日工时识别
- 低精度定位过滤与 60 分钟离岗确认
- 动态省电采样：移动/围栏边缘 1 分钟、上下班窗口 5 分钟、普通状态 10 分钟、稳定在家或公司 30 分钟
- 日历、月度统计、手动修正与数据导出
- 工资所属月份与实际发放日期分离
- Room 数据库无损迁移

所有工时、定位和工资数据默认只保存在手机本地。

## 技术栈

- Kotlin
- Jetpack Compose / Material 3
- Room
- WorkManager
- Android Foreground Location Service
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- 最低 Android 10（API 29）
- 目标 Android API 35

## 构建

准备 JDK 17 和 Android SDK，并在项目根目录创建本机专用的 `local.properties`：

```properties
sdk.dir=C\:\\Users\\your-name\\AppData\\Local\\Android\\Sdk
```

Windows：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS / Linux：

```bash
./gradlew testDebugUnitTest assembleDebug
```

调试 APK 生成在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 权限说明

自动记录需要定位、后台定位、通知和前台服务权限。Android 会持续显示必要的前台服务通知，这是后台定位的系统要求。

## 隐私

项目不依赖账号或后端服务器。定位轨迹、工时记录、工资金额等敏感数据不应提交到 Git 仓库。

## 许可证

参见 [LICENSE](LICENSE)。
