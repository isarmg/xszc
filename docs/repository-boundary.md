# 仓库职责

本仓库构建三个 Rust crate 与 Android/iOS 应用：`crypto` 负责内容处理，`client-core` 负责移动队列和图库业务，`mobile-ffi` 提供 C/JNI 边界。平台宿主拥有系统照片权限、UI、网络调度和安全存储。

线上协议 DTO 来自 [xszs](https://github.com/isarmg/xszs) 的固定 Git revision。服务端认证、管理 Web、对象存储和部署在该仓库维护；客户端构建通过 Cargo 获取依赖，无需相邻源码目录。

软件发行版本来自根 `VERSION`。软件 1.1.4 继续使用移动合同 1.0.0、epoch `xszc-mobile-v1`、数据库与移动 revision 2 和 ABI 1，详见[状态身份](reference/protocol-and-state.md)。

Android 应用 ID 为 `org.sarmg.xszc`，正式更新沿用既有签名证书。发布过程和秘密输入由受保护的 `android-signing` 环境管理；iOS 交付未签名 IPA，由安装者按 Apple 部署方式签名。详见[构建](development.md)和[签名](android-signing.md)。
