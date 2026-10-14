# 构建与测试

在仓库根目录执行下列命令。Rust 固定 `1.99.0`；按目标选择 [Android](platforms/android.md#从源码构建)或 [iOS](platforms/ios.md#从源码构建)工具链。三个 Rust crate 分别负责内容校验、移动业务核心与 FFI。

## Rust 和公共检查

```sh
./scripts/check-mobile-contract.sh
./scripts/check-workflow-supply-chain.sh
cargo fmt --all -- --check
cargo check --workspace --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
./scripts/test-mobile-ffi-c.sh
```

合同检查覆盖 Rust/Kotlin/Swift、生成的 C 头、状态身份与 DTO。C 测试实际加载宿主动态库；原生设备测试按后续章节执行。

## Android

Android 的工具链、构建、设备安装和原生测试见 [Android 指南](platforms/android.md#从源码构建)。


## iOS

iOS 的工具链、构建、设备安装和原生测试见 [iOS 指南](platforms/ios.md#从源码构建)。


## 发布验证

在干净且已取得对应标签的发行 checkout 执行 `./scripts/verify-release-version.sh v1.0.0`。正式工作流生成签名 Android APK、未签名 iOS IPA 和 `SHA256SUMS`。核对同一提交的主 CI、发布工作流及下载资产，再记录设备验收结果。

物料工具从 `build-tools-source.json` 固定的 `xssi` 提交取得，按实际 Cargo 目标与 feature、静态 SQLite 原始输入、Android `releaseRuntimeClasspath` 解析图及最终 DEX/原生库生成 SBOM。iOS 记录最终应用可执行文件。只接受当前干净源码和确实存在的构建输入；工具修订未固定时打包会明确失败。

最终交付目录附带 SBOM、`share/package.json` 和 `share/package.sha256`，发行校验使用 SHA-256。Android 固定平台签名身份保持原有流程；iOS 未签名包仍须按平台部署要求完成签名后安装。

图库、视频、取消和权限变化的详细设备场景见[实现与验收](manual-backup-implementation.md#设备验收)。模拟器结果与真机后台调度、触控和解码兼容性分别记录。
