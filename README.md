# Android WebView Updater

一个纯 Java、Material You 风格的 Android 系统 WebView 更新工具。

## 功能

- 读取当前系统 WebView 提供者（`WebView.getCurrentWebViewPackage()`）的包名、版本名与 versionCode
- 依据 Android 主版本号请求梨的 webview 托管服务，**全程仅 HTTPS**
- 若服务器上的版本更高，弹窗询问是否下载并安装
- 下载时实时显示进度（已下载 / 总大小 / 百分比）与速度
- 连接超过 **10 秒** 仍未建立时自动取消，弹窗进行提示
- 下载完成后通过 FileProvider 唤起系统安装器（自动引导开启「安装未知应用」权限）
- 自适应应用图标（含 Android 13+ 单色主题图标）与矢量应用内图标

## 目录结构

```
.
├── .github/workflows/
│   ├── build.yml                      # GitHub Actions：构建 APK 并自动发布 Nightly Release
│   ├── font-subset.yml                # GitHub Actions：子集化 Nebulove 字体并内嵌到网页
│   └── static.yml                     # GitHub Actions：部署静态展示页到 GitHub Pages
├── scripts/subset_font.py             # 网页字体子集化脚本
├── web/                               # 项目展示网页（GitHub Pages）
├── build.gradle / settings.gradle / gradle.properties
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/webviewupdater/app/MainActivity.java
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/                                     # 应用内矢量图标与署名头像
            ├── mipmap-anydpi-v26/ic_launcher.xml             # 自适应图标定义
            ├── mipmap-xxhdpi/                                # 自适应图标前景 / 背景 / 单色图层
            ├── values/, values-night/                        # 主题 / 颜色 / 字符串
            └── xml/
                ├── file_paths.xml                            # FileProvider 路径配置
                └── network_security_config.xml               # 网络安全配置（仅允许 HTTPS）
```

## 构建

- 要求：JDK 17、Android SDK 34
- 本地：`gradle assembleRelease`（工程未附带 wrapper，可先执行 `gradle wrapper --gradle-version 8.7`）
- CI：推送代码变更或手动触发即可构建 `WebViewUpdater-v1.0.0-release.apk` 并自动创建 `nightly-<run_number>` GitHub Release

> [!tip]
> Release 构建默认使用 debug 密钥签名，便于直接安装测试。请在 `app/build.gradle` 中配置自己的 signingConfig。

## 回归测试

仅需 Python 3.11+、Node.js 22+ 和 Git，无需安装 Python / npm 依赖或访问网络：

```sh
python3 -m unittest discover -s tests -v
node --test tests/*.test.cjs
```

测试覆盖字体替换范围与原子写回、工作流提交身份的安全传递、Android 资源配置，
以及网页入场动画和主题状态。字体测试使用确定性替身，不下载字体；网页测试使用
轻量 DOM 替身，不代替真实浏览器兼容性测试。推送和 Pull Request 会自动运行这些测试。

Android 编译与静态检查另行执行 `gradle assembleRelease lintRelease --no-daemon`。
下载、安装权限返回及 Activity 重建仍需在 Android 设备或模拟器上验证。

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
