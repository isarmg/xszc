# 客户端功能索引

本页索引 xszc 当前源码候选 1.1.5 的实际移动功能。详细使用步骤见[日常使用](usage.md)，实现预算和验收场景见[备份与图库参考](manual-backup-implementation.md)。

| 功能 | 使用入口 | 主要实现 |
|---|---|---|
| 手动/自动备份 | 本地、设置、传输 | `client-core` 队列；Android BackupWorker；iOS BackupCoordinator |
| 分块续传与完成回执 | 传输 | Rust 持久任务、prepared parts、backup_receipts |
| 全量轻量本地目录 | 本地 | MediaStore / PhotoKit，增量更新及按需缩略图 |
| 分页云端图库与离线缓存 | 云端 | RemoteLibrary、Rust gallery 缓存和同步游标 |
| 收藏、归档、相册、回收站、重复项 | 云端菜单 | 设备授权的 `/v1` 接口 |
| 原件下载与校验 | 云端下载、传输 | 平台下载队列、Rust size/BLAKE3 校验、系统照片库 |
| 全屏视频 | 本地或云端预览 | Android Media3 / iOS AVPlayer，分段 HTTPS 读取 |
| 照片授权与后台调度 | 设置、系统权限 | Android WorkManager / iOS PhotoKit、BGProcessingTask |
| 私有凭据 | 账户登录 | Android Keystore 加密偏好 / iOS Keychain |
| 构建和发行 | 开发工具 | Rust/C/JNI、Gradle、Xcode、APK 签名、IPA 打包 |

## 取舍

- 使用两端原生界面和系统照片 API，公共业务放在 Rust 核心。
- 原件按原始字节备份，服务端保存媒体；当前未提供端到端加密。
- 队列和回执持久化，后台运行时机由操作系统决定。iOS 后台 URLSession 进程重启交接的限制见[宿主参考](beginner-guide/06-android-and-ios-clients.md#ios-扫描)。
- 浏览缓存可重建，上传分块与队列属于待传状态，分别管理。
- 当前功能集中于备份和图库，没有人脸识别、AI 分类、共享相册或复杂编辑。

服务端配额、对象存储、管理账户与部署由 [xszs](https://github.com/isarmg/xszs/tree/main/docs)负责。
