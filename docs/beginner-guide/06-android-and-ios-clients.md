# Android 与 iOS 宿主参考

本页供实现平台扫描、后台调度和 FFI 时查阅。首次构建见[构建与测试](../development.md)。

## 共同状态机

```text
未配置 -> 保存当前 HTTPS/实例授权码 -> 配对取得设备 Token
 -> 请求照片权限 -> 选择/排除相册 -> 扫描
 -> durable enqueue -> background upload -> sync cursor
 -> timeline browse -> authenticated download -> system restore
```

两端必须表达相同产品/version/revision/epoch 和 `plain-v1`，但不要求 UI 代码共用。

## Android 扫描

`MediaScanner` 通过 ContentResolver/MediaStore 查询并遍历 Cursor，以入队数量预算限制本轮准备，
不使用 LIMIT/OFFSET 分页。它使用稳定系统 ID、修改时间、MIME、大小和
相册关系构造候选。`DeviceAlbums` 管理选择/排除。不要在主线程读取大文件，也不要假设 content URI 是
普通文件路径。

`BackupScheduler` 配置 WorkManager 网络/电量策略，`BackupWorker` 驱动 Rust queue 与 `BackupApi`。
Worker 重启时从持久状态继续，不能仅依赖 Compose 内存状态。Android 只有在扫描未截断、未启用“仅相机目录”、照片和视频均启用且媒体访问为完整授权时，才请求替换相册成员；其他情况使用
`replace_members=false` 追加同步。请求超过 10,000 个成员时拆分，替换标志只用于首批，后续批次追加。

“从持久化的 state 继续”必须按状态理解：`ready` job 和带 `prepared_json` 的到期 `retry_wait` job 都会
复用已经持久化的 part，无需读取导出临时源；进程重启还会把带准备结果的 `preparing` 或
`uploading` 恢复成 `ready`。没有准备结果的任务才回到 `discovered` 并重新读取源文件。每次准备写入新的
代次，成功持久化后回收同一任务的旧代次；准备中途失败的未引用代次要等后续成功
准备才能被回收。不能用清空整个暂存区的方式排障。

## Android 秘密与权限

服务地址、实例授权码、Token、用户设置和备份状态保存为一个 AES-256-GCM 认证加密快照；随机 IV 由平台 Cipher 生成，
密钥由 Android Keystore 创建并持有，不导出到文件。密文位于应用私有 `noBackupFilesDir/xszc_secure_v1.bin`，
通过平台 AtomicFile 替换；同步写入、同步磁盘与回读确认完成后才报告保存成功。所有配置实例在同一应用进程内共享事务锁，
登录凭据、退出状态、整组备份设置及备份快照不会发生部分字段提交；设置页按一份快照读取账户与偏好，避免重复解密和不一致读取。退出后仍保留用于用户再次登录的原凭据，但普通连接不再返回 Token。
日志、Intent、SavedState 和崩溃报告不包含凭据。

首次只读不会创建密钥。旧 SharedPreferences 文件、损坏或未完成的快照、已有状态仅剩文件或密钥、解密失败均明确报错并保留现状，
不会回退到空身份、自动换密钥或迁移旧格式。凭据和密钥均缺失但当前实例数据库仍在时也拒绝初始化；首次未配对浏览生成的
空身份图库索引不属于实例队列。首次保存失败或中断后，若仅留下密钥而没有快照，即使随后存储恢复也不会猜测可以重新初始化，需要保留现场并明确处置。当前开发版本的格式不受支持时，先保留现场和仍需的本地任务数据，再由用户明确新安装并重新配对。
新安装不代表迁移旧任务，也不撤销远端凭据，不能作为通用排障动作。启动失败显示只读错误页和“重新读取”入口，不自动清除数据；
导航、本地/云端图库、传输页、登录、退出和设置读取/写入失败也进入该错误页；单纯后台调度故障单独提示，不误报配置损坏。后台 Worker 将配置故障记录为 `failure_kind=local_config` 的失败结果，
保留原任务并停止按网络故障重试；上传中途发现配置故障会将资源保留为可恢复任务，不标成媒体永久失败。发布快照后的回读失败表示保存结果无法确认，必须重新读取实际状态，不再调用发布前回滚。
Android 正式 package/app ID 只使用当前命名空间，不探测旧 database 或暂存区。

实现直接使用 [Android Keystore](https://developer.android.com/privacy-and-security/keystore) 与
[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)，不再依赖已弃用的 AndroidX Security Crypto。
`EncryptedConfigStoreTest` 在 JVM 上验证真实 AES-GCM 加解密、篡改拒绝、身份隔离、并发事务和注入写入失败；
`SecureConfigInstrumentedTest` 在 Android 上验证平台密钥不可导出、重开/退出语义、丢失文件或密钥及旧格式拒绝。
平台测试随 `connectedDebugAndroidTest` 执行；本地 JVM 测试不能代替 Android Keystore 与 AtomicFile 的设备验证。

正式应用 ID 是 `org.sarmg.xszc`，Kotlin 路径、命名空间、JNI 导出名和 APK badging 必须
同时一致。Debug APK 只用于 CI/开发，永远不取得正式秘密；Release 使用受保护 Environment 中两个
PKCS#12 秘密，并在发布前核对唯一签名者、固定证书指纹和唯一 `arm64-v8a` JNI。安装包仅支持当前
package/证书身份，JNI 仅导出当前 ABI 符号。

## iOS 扫描

`PhotoScanner` 通过 PhotoKit 查询结果和授权范围读取 asset/album，必要时请求资源流。
有限照片库权限下扫描只覆盖当前授权集合，并以 `replace_members=false` 追加同步成员。iOS 完整授权时请求替换；超过 10,000 个成员会拆分，只有首批保留替换标志，后续批次追加。不能把有限授权的可见集合当作完整照片库。

`BackupCoordinator` 管理扫描/队列/后台任务，`BackgroundUploader` 用 `taskDescription` 关联 URLSession
task 与持久化的 job。当前 `AppDelegate` 只注册 BGProcessingTask，没有实现后台 URLSession 的
进程重新启动后的完成回调交接；因此只能保证现有 delegate 生命周期内的回调，不能宣称杀进程后已闭环。
`BackgroundUploader.submit` 逐块等待上传回调并提交完成回执；自动扫描中的队列处理完成后同步相册。
本轮尚未备份的资产成员可在后续运行中补齐。

## iOS 秘密与恢复

Token 存 Keychain。恢复路径先取得 manifest，再将下载文件写入私有暂存目录并调用 Rust 校验
size/BLAKE3；校验通过后才交给 PhotoKit `performChanges`。Android 在发布到 MediaStore 前采用相同
校验。权限拒绝、空间不足和照片库写入失败仍会中止恢复。

## FFI 规则

当前 C ABI 为 xcsc revision 1：`xszc_*_v1` 接收显式指针/长度和 `XcscFfiResultV1` 输出，返回状态码。
生成 Header 只有 `xszc_ffi_v1.h`；结果中的字节和错误消息均有明确长度，必须通过
`xcsc_ffi_result_free_v1` 释放整个结果，不能复制后重复释放。
Swift 通过 XCFramework 的 `XszcRust` C module 导入 Header；
Kotlin 使用 `NativeBridgeV1`，JNI 失败抛出 xcsc 映射的异常，不把默认值当成功。两个宿主都先验证
ABI revision；业务配置仍严格验证当前 product/version/revision/state_epoch，ABI revision 不等于状态 epoch。
Rust panic 经共享边界转成 255，panic 内容不写入宿主日志；`panic=abort` 构建会被拒绝。
移动 `part_size` 限制为 1 字节至 64 MiB，单文件最多 4096 个分块；超限配置在创建数据库前拒绝，
已知超限文件在创建分块目录前拒绝。分块缓冲区使用可失败的分配，避免配置触发无界分配。
验证时分别记录 C 动态库、Rust 测试及 Android/iOS 原生运行结果；静态合同检查用于提前发现接口漂移。

## UI 可观察状态

至少区分扫描中、待上传、正在上传、可重试失败、永久拒绝、同步中和恢复结果。显示统计不能成为状态
事实源；重启后从 SQLite/API 重建。取消只是停止本轮工作，是否删除持久化的 job 必须由明确用户动作决定。
