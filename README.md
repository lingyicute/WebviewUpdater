# WebView 更新器（Android）

一个纯 Java、Material You（Material 3 + 动态取色）风格的系统 WebView 更新工具。

## 功能

- 读取当前系统 WebView 提供者（`WebView.getCurrentWebViewPackage()`）的包名、版本名与 versionCode
- 依据 Android 主版本号请求 `https://webview-ver.92li.uk/<android版本数字>`，
  解析 `"<versionCode> <apk下载链接>"` 格式响应
- 若服务器版本更新，弹窗询问是否下载并安装
- 下载时实时显示进度（已下载 / 总大小 / 百分比）与速度
- 连接超过 **10 秒** 仍未建立：自动取消下载并弹窗提示「请连接 VPN 后再试」
- 下载完成后通过 FileProvider 唤起系统安装器（自动引导开启「安装未知应用」权限）
- 自适应矢量图标（含 Android 13+ 单色主题图标）

## 目录结构

```
.
├── .github/workflows/build.yml        # GitHub Actions：构建并上传 APK，打 tag 自动发 Release
├── build.gradle / settings.gradle / gradle.properties
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/webviewupdater/app/MainActivity.java
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/ic_launcher_*.xml, ic_app_logo.xml   # 矢量图标
            ├── mipmap-anydpi-v26/ic_launcher*.xml           # 自适应图标
            ├── values/, values-night/                       # 主题 / 颜色 / 字符串
            └── xml/file_paths.xml                           # FileProvider
```

## 构建

- 要求：JDK 17、Android SDK 34
- 本地：`gradle assembleRelease`（工程未附带 wrapper，可先执行 `gradle wrapper --gradle-version 8.7`）
- CI：推送到 `main`/`master` 或手动触发即产出 `WebViewUpdater-apk` 构件；推送 `v*` 标签会自动创建 GitHub Release

> Release 构建默认使用 debug 密钥签名，便于直接安装测试。正式分发请在 `app/build.gradle` 中配置自己的 signingConfig。

## 说明

- 「Android 版本数字」取自 `Build.VERSION.RELEASE` 的主版本（如 `14`、`8.1.0 -> 8`）。
  若服务端使用 API level，可将 `MainActivity.androidMajorVersion()` 改为返回 `Build.VERSION.SDK_INT`。
- minSdk 26（Android 8.0+）。
