# 客户端发行

正式发行从干净的精确版本标签构建，标签必须与根 `VERSION`、Android `versionName` 和 iOS `MARKETING_VERSION` 一致。移动持久状态和 ABI 身份分别检查。

Android 生成最低 Android 17/API 37、同签名、仅 arm64-v8a 的正式 APK。iOS 生成最低 iOS 27.0 的未签名 IPA，由安装者按自己的 Apple 部署方式签名。只采用当前稳定系统系列与稳定 SDK，发布后核对同一源码的主 CI、Release 工作流和下载资产。

- [构建和发布验证](../development.md#发布验证)
- [Android 签名材料与证书核验](../android-signing.md)
- [安装、更新和卸载](../platform-setup.md)
- [队列与凭据维护](../operations.md)

服务端的反向代理、systemd、数据库和存储管理使用 [xszs 文档](https://github.com/isarmg/xszs/tree/main/docs)。
