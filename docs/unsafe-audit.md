# 依赖和 unsafe 审查

本文保留 1.1.0 / xcsc 1.0.0 阶段的历史审计正文及后续注明日期的历史记录，不作为当前候选的新验收；当前版本及验证边界见 [1.1.3 发行说明](releases/1.1.3.md)。

本轮候选固定 Rust 1.99.0；数据库采用 SQLx 0.9.0 的 SQLite bundled driver 与 libsqlite3-sys 0.37.0。SQLx 的直接 SQLite 连接不依赖特定 runtime；本产品使用该 worker 和真实 SQLx 事务保留同步移动接口，未引入 SQL 字符串解释器。查询中的数据均通过参数绑定；动态查询仅由固定语句和已转义、受控表名组成。参见 [SQLx 官方 runtime 说明](https://docs.rs/sqlx/0.9.0/sqlx/#runtime-support)。SHA-2 更新为 [0.11.0](https://docs.rs/sha2/0.11.0/sha2/)。JNI 精确消费 0.22.4，与共享 EnvUnowned / Env guard 同一类型图；Java 和 C ABI 不变。同步 worker future 使用公共无 SQLx 依赖的桥。

移除无消费者的工作区依赖声明；Cargo.lock 不含 rusqlite。当前 SQLite 结构定义、数据身份、C ABI 和 Android/iOS 功能均继续由各自的产品合同约束，不从软件发行号推导。

| 保留的 unsafe | 必要性和约束 | 验证 |
|---|---|---|
| crates/mobile-ffi/src/lib.rs 的 12 个 C ABI 入口 | C 提供原始指针，不能用 Rust 引用替换现有 ABI。入口明确文档化指针、长度、结果所有权；非空指针的实际可读范围、结果对齐和独占可写生命周期仍由 C 调用者保证，空值检查不能证明任意地址有效；统一 xcsc 检查字节预算、UTF-8、panic 边界和结果释放。业务内部使用安全 Rust。 | 当前 ABI 开关、重复关闭、无效输入零写入、静态错误和共享 C/JNI 原生夹具。 |
| database/sandbox_vfs.rs::register 的 VFS 注册 | 必须向 SQLite C ABI 注册原生 Unix VFS 的克隆，保留其锁、WAL 和 I/O；注册成功的对象具有进程生命周期，失败立即回收。SQLx 本身没有提供所需的移动容器路径回调。 | 当前数据原行保留与实际打开；注册后 callback 边界、路径别名拒绝。 |
| sandbox_vfs.rs::open_nofollow 的 xOpen 调用 | C callback 参数由 SQLite 提供，原 callback 及 ABI 完整保留，仅增加 SQLITE_OPEN_NOFOLLOW；不复制数据库驱动。 | 实际日志软链接拒绝且目标文件、主库字节保持不变。 |
| sandbox_vfs.rs::full_pathname 的 CStr 和输出复制 | 原生 C 输入为 NUL 字符串，输出使用 SQLite 提供的显式容量；先检查空指针、大小、私有真实文件路径，然后复制并加 NUL，panic 不跨 C 边界。 | 小缓冲区、空指针、软链接和尾部哨兵字节测试。 |
| 测试中的 ABI 调用 | 使用存活的 CString/字节切片、已初始化结果及有界输出；没有持久化运行代码的额外权限。 | 测试后释放每个拥有的结果。 |

SQLx 产品连接和事务封装没有 unsafe。Rust slice 复制可替代一般缓冲区操作；此处保留的原始复制仅限 SQLite 的 C 输出 ABI。公共私有文件、锁和移动 FFI 机制继续由 xcsc 提供，产品只保留数据库和移动业务语义。原生平台验证结果按实际源码单独记录，Linux 成功不代表 Android/iOS 已通过。

共享 xcsc 输入固定 1.0.0 / c45e48e93e360542c2e1db6c6441a9e29b344b03。该源码延续 Android/iOS 对端身份、进程所有者或 root 检查，并加入共享服务捕获与源码约束修复；对应最终源码原生 CI 单独核验。公共库发行资产和本产品最终 Android/iOS 执行分别核对，不用公共编译代替产品用户路径。

## 当前候选工程约束

正式状态以 Git tag、最终源码工作流和 Release 产物为准。Rust 1.99.0 是截至 2026-10-07 的当前正式版；Tokio 选择稳定的 ~1.53.2，兼容补丁由根 Cargo.lock 锁定。unsafe 函数内的原始解引用和外部函数调用必须放进显式 unsafe 块（unsafe_op_in_unsafe_fn = deny）。这项约束检查操作边界，不替代原生 ABI、权限与生命周期验证。正式输入和用户数据身份分开记录，不通过发行号推导持久状态。

当前软件发行号为 1.1.0，移动状态 1.0.0、ABI 1 保留；xcsc-client.toml 修正为实际精确消费的 xcsc 1.0.0。产品协议 crate 1.0.0 固定官方服务端源 c0aa705df39d3187166f62af4faab7c821be9baa，移动传输结构不随 crate 版本改变；用户已有 iOS 26 与 IPA 修复保留并纳入原生脚本验证。

1.1.0 加入全量轻量本地目录、按 ID 读取详情和增量目录更新，使用参数绑定及现有 SQLite 事务；未新增 unsafe、FFI 签名、依赖或结构定义变更。两端改为按需缩略图和全屏视频播放，验证入口与原生执行边界见 [1.1.0 说明](releases/1.1.0.md)。

历史记录中的名称已规范，历史版本、提交与验收状态保持不变，不作为当前版本的验收证据。仓库发布页只保留最新 1.1.0；早期功能和验证事实继续保留在以下记录中。

1.0.0 修复 Android/iOS 本地图库 150 项后的分页与自动刷新冲突，保留已加载页数并用额外一项判断真实分页结尾。新增图库边界、筛选和生命周期回归验证；未修改 Rust 行为、FFI、依赖、数据库或持久身份。这一记录属于历史版本；当前原生构建、签名与制品的验收边界见 [1.1.0 说明](releases/1.1.0.md)。

0.6.4 调整原生图库、登录和传输业务，并新增安全 Rust 队列查询与完成批次校验；未新增 unsafe 或改变 FFI 签名、依赖、数据库身份。下载哈希校验的 1 MiB 缓冲区直接在堆上分配，新增 256 KiB 栈上的真实文件校验测试。本机 52 项 Rust、C17 ABI、真实 JVM/JNI、Android 17 项单元及 24 项原生、iOS 31 项单元、5 项 IPA 行为、移动合同和 Action 供给链检查通过。macOS Rust 测试使用规范路径 `/private/tmp`，避免系统临时目录别名被 NOFOLLOW 策略拒绝；上述数值保留为历史证据，当前版本仍须分别验收最终平台 CI 与正式资产，参见 [1.1.0 说明](releases/1.1.0.md)。下列 0.6.3 与 0.6.2 记录为历史证据。

0.6.3 修复真实照片权限提示的 UI 测试处理，并固定经审查的构建工具输入；Rust 业务、FFI、unsafe、依赖和数据合同保持。全新 iOS 26.5 模拟器未预授 TCC，实际点击系统“允许完全访问”，待完整访问状态与测试媒体载入后，两个完整 UI 测试通过（0 失败），全部布局、登录与两轮系统选择器断言保留。重新构建设备和模拟器 Rust 切片，5 项 IPA 打包器行为、移动合同、33 项 Action 供给链 gate 和 Rust fmt 通过。

46 项 Rust、C17、15 项 iOS 单元、实际 Android JNI/JVM 与 Android 16 的 6 项结果来自 0.6.2 精确源码 43db1259cbf58109f7e001796828e38d03e0df98 的主 CI；其主 CI 第二 attempt 通过，第一次 iOS 单元步骤超时。0.6.2 正式三项资产独立验收通过，IPA 无 `.a` 且 ABI 定义、字节和权限完整；但该版本标签 CI 的两项 UI 中有一项因权限辅助逻辑失败，15 项单元仍通过。此失败促成本版修复，未修改旧标签或资产。最终新源码 CI 与正式资产分别记录，不将旧平台结果写成本版重跑证据；当前验收边界见 [1.1.0 说明](releases/1.1.0.md)。

## 统一规范验收边界

| 适用条款 | 当前实现与本轮验收 | 真实限制 |
|---|---|---|
| 2–5、19：职责、目录与身份 | Rust 工作区的client-core、crypto、mobile-ffi是真实独立职责；clients/android、clients/ios拥有UI/系统照片机制。只保留根Cargo.lock。软件1.1.0、mobile-v1、schema1、epoch、C/JNI ABI1分别校验，不按发行号迁移队列。 | 移动不是桌面daemon；CLI/SCM/Web约束不强行植入移动应用。 |
| 6–8、11–14：状态和副作用 | 固定schema/hash、NOFOLLOW VFS、真实SQLx事务与私有目录校验；实例/照片/task稳定身份、prepared parts和SQL状态协调。授权码轮换保留同实例队列，错实例拒绝；取消与完成分开；照片和prepared bytes有预算。 | 设备权限、PhotoKit/WorkManager后台调度由OS决定，UI启用不等于后台传输已完成。 |
| 15–16：日志和界面 | 账户、Token、授权码不进入诊断；Android私有配置、iOS Keychain及UI区分照片权限、排队、传输和完成。 | Native UI、真机照片权限/后台以及实际安装与签名需最终平台执行证明。 |
| 20–23：验证与发行 | 0.6.4 本机验证见上文，历史 0.6.3、0.6.2 证据独立保留。cargo-ndk4.1.2、Temurin官方SemVer17.0.20+101精确安装，实际runtime17.0.20.1+1和XcodeGen2.46.0在构建前验证。 | 模拟器不代替真机安装/照片后台行为。新版本 Release 须再次核验 Android 唯一签名者和 iOS 未签名标志。 |


## 2026-10-10 xcsc 1.0.1 输入更新（历史记录）

截至 2026-10-10，该次记录的依赖更新为官方 `xcsc =1.0.1`，固定 Git 修订 `d3e9b8db84e4ead70ec0bf8a596dbad697f7db24`，并同步产品能力清单和 `Cargo.lock`。上文关于 xcsc 1.0.0 的来源与验证描述保留为当时记录，不代表当前输入。本节所述当时修订的客户端来源校验和 `cargo metadata --locked --all-features` 已通过；这两项不替代本产品最终源码的原生平台、实体设备或业务路径验收。

当前发行使用的精确支撑库版本与修订以 `Cargo.toml`、`Cargo.lock` 和本版发行说明为准；本节历史验证结论不构成新版本的安全审计或原生平台验收。
