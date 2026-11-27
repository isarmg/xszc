# 任务流程与源码导航

## 一次备份

1. 原生页面读取实际照片权限、选择媒体或扫描自动相册。
2. 宿主导出获准访问的资源，Rust 核心持久化批次和任务。
3. Rust 准备分块、计算哈希并保存准备结果。
4. 宿主通过 HTTPS 创建 upload、发送缺失分块并提交完成。
5. 核心保存回执，平台界面更新传输与备份状态。

重复选择复用同来源的未变资源。`retry_wait` 到期且有准备结果时继续使用已落盘分块；重开把已有准备结果的 `preparing`/`uploading` 恢复为 `ready`，没有准备结果的任务回到 `discovered`。

## 图库与下载

本地目录跟随 MediaStore/PhotoKit 变化更新。云端首屏独立分页，完整元数据快照和增量事件在后台同步，数据库事务同时更新资源与游标。下载将原件放入私有暂存，验证大小和 BLAKE3 后，再写入系统照片库。

## 按任务找源码

| 修改任务 | 阅读入口 |
|---|---|
| 队列、批次、回执、图库缓存 | `crates/client-core/src/` 与 `schema/` |
| 分块和内容校验 | `crates/crypto/src/` |
| C/JNI ABI | `crates/mobile-ffi/src/` 与 `crates/mobile-ffi/include/` |
| Android 页面、相册和后台 | `clients/android/app/src/main/java/org/sarmg/ana/` |
| iOS 页面、PhotoKit 和后台 | `clients/ios/Ana/` |
| 原生构建、签名和打包 | `scripts/`、Android Gradle 配置、iOS `project.yml` |

## 变更验证

先为改动运行相邻模块测试，再按[构建与测试](development.md)执行公共和目标平台检查。协议类型按 Cargo 中固定的 ana-server revision 阅读；修改跨端 DTO 时同步 Rust、Kotlin、Swift 和合同检查。

发布 APK/IPA 时分别核对应用身份、软件版本、ABI、签名或未签名标记。签名策略见[Android 正式签名](android-signing.md)，完整设备场景见[验收参考](manual-backup-implementation.md#设备验收)。
