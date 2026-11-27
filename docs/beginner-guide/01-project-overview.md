# 项目和模块

ana 将获准访问的手机媒体备份到自己的 ana-server 服务端。原生层负责照片权限、界面、安全存储和后台调度，Rust 核心负责持久队列、内容校验和业务状态。

## 阅读顺序

1. `Cargo.toml` 与各 crate 清单：理解依赖方向。
2. `crates/client-core/src/lib.rs`：理解队列与图库入口。
3. `crates/crypto/src/lib.rs`：理解分块和哈希。
4. `crates/mobile-ffi/src/lib.rs`：理解平台桥。
5. Android `BackupWorker.kt` / iOS `BackupCoordinator.swift`：跟踪宿主调用。

手机的队列、prepared 分块与安全凭据是本地状态；已上传媒体和服务端元数据由 ana-server 管理。TLS 保护网络传输，服务端保存原始媒体字节，服务器管理员能够读取它们。

下一步：[构建开发版本](02-environment-and-first-run.md)。状态身份详见[协议参考](../reference/protocol-and-state.md)。
