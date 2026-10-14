# xszc 文档


当前发布准备：[1.1.5 候选说明](releases/1.1.5.md)。完整平台验收与正式资产以该版本最终工作流为准。
xszc 将 Android 和 iOS 上获准访问的照片、视频备份到自己的 xszs 服务端，并提供本地/云端图库、传输进度与原件下载。

## 选择平台

- [Android](platforms/android.md)：Android 17/API 37+ arm64，正式 APK、Debug 构建、签名与 ADB 排障
- [iOS](platforms/ios.md)：iOS 27.0+，IPA 签名、Mac 构建、模拟器与 Keychain 排障

Linux、Windows 和 macOS 是开发机选项；本产品仅提供 Android 和 iOS 应用。

## 安装和使用

1. [安装应用](platform-setup.md)：Android APK、iOS 签名与覆盖更新
2. [完成第一次备份](getting-started.md)：登录、照片权限、手动上传和检查结果
3. [设置备份范围](configuration.md)：网络、电源、媒体、相册和缓存默认值
4. [日常使用](usage.md)：选择备份、查看图库、下载、取消任务和退出
5. [排查问题](troubleshooting.md)：登录、权限、后台任务、签名和队列错误

## 开发和维护

- [构建与测试](development.md)：Rust、Android 和 iOS 开发环境与验证
- [维护应用数据](operations.md)：队列、临时文件、凭据及故障证据
- [开发者导读](beginner-guide/README.md)与[参考索引](reference/README.md)
- [1.1.0 发布说明](releases/1.1.0.md)

服务端安装和管理使用 [xszs 文档](https://github.com/isarmg/xszs/tree/main/docs)。本仓库维护移动核心、FFI 与两端原生应用。第一方代码、文档和资源采用 [Apache License 2.0](../LICENSE)。
