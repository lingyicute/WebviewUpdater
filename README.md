# Android WebView Updater

一个纯 Java、Material You 风格的 Android 系统 WebView 更新工具。

## 功能

- 读取当前系统 WebView 提供者（`WebView.getCurrentWebViewPackage()`）的包名、版本名与 versionCode
- 依据 Android 主版本号请求梨的 webview 托管服务
- 若服务器上的版本更高，弹窗询问是否下载并安装
- 下载时实时显示进度（已下载 / 总大小 / 百分比）与速度
- 连接超过 **10 秒** 仍未建立时自动取消下载并弹窗提示
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
            ├── mipmap-anydpi-v26/ic_launcher*.xml            # 自适应图标
            ├── values/, values-night/                        # 主题 / 颜色 / 字符串
            └── xml/file_paths.xml                            # FileProvider
```

## 构建

- 要求：JDK 17、Android SDK 34
- 本地：`gradle assembleRelease`（工程未附带 wrapper，可先执行 `gradle wrapper --gradle-version 8.7`）
- CI：推送到 `main`/`master` 或手动触发即可产出 `WebViewUpdater-apk` 构件并自动创建 GitHub Release

> Release 构建默认使用 debug 密钥签名，便于直接安装测试。请在 `app/build.gradle` 中配置自己的 signingConfig。

## 说明

- 「Android 版本数字」取自 `Build.VERSION.RELEASE` 的主版本（如 `14`、`8.1.0 -> 8`）。
- minSdk 26（Android 8.0+）。

## 🗂️ License

WebviewUpdater is released under the GNU General Public License v3.0 (GPLv3).

Copyright (C) 2024-2026 lingyicute.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see https://www.gnu.org/licenses.
