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

`LocalGallerySelectionTest` 在 JVM 上执行页面实际使用的 Kotlin 选择状态；可用 `gradle -p clients/android testDebugUnitTest --tests org.sarmg.xszc.LocalGallerySelectionTest` 单独运行。测试控制挂起的详情读取，检查批量完成后的实际选中集合，以及取消、重新进入、单选意图、筛选变化和协程取消后的迟到结果。


## iOS

iOS 的工具链、构建、设备安装和原生测试见 [iOS 指南](platforms/ios.md#从源码构建)。

`LocalGallerySelectionTests` 属于现有 `XszcTests`，在 iOS 模拟器执行页面实际使用的 Swift 选择状态，覆盖与 Android 相同的批量完成及迟到结果边界。它验证最终状态，不将 Swift 源码条件翻译成 Python；PhotoKit、MediaStore 和手势行为仍由各平台设备/UI 测试验证。


## 发布验证

先让待发布提交在分支上完成 `Build and test`，再创建对应标签。在干净且已取得对应标签的发行 checkout 执行 `./scripts/verify-release-version.sh v1.0.0`。发布工作流以标签触发时的固定 SHA 检出源码，将解引用后的 commit SHA 交给 `check-release-ci.py`，要求该提交最新的分支 CI 运行及 Rust、Android、iOS 模拟器、iOS 实机编译与 iOS 汇总任务全部成功；PR、其他提交、跳过、取消和后续失败均不能借用旧的成功记录。检查只读取现有 CI 结果，不重复调度整套移动测试。CI 未完成或失败时先处理对应运行，再重跑发布验证。

正式工作流生成签名 Android APK、未签名 iOS IPA 和 `SHA256SUMS`。核对同一提交的主 CI、发布工作流及下载资产，再记录设备验收结果。

物料工具从 `build-tools-source.json` 固定的 `xssi` 提交取得，按实际 Cargo 目标与 feature、静态 SQLite 原始输入、Android `releaseRuntimeClasspath` 解析图及最终 DEX/原生库生成 SBOM。iOS 记录最终应用可执行文件。只接受当前干净源码和确实存在的构建输入；工具修订未固定时打包会明确失败。

最终交付目录附带 SBOM、`share/package.json` 和 `share/package.sha256`，发行校验使用 SHA-256。Android 固定平台签名身份保持原有流程；iOS 未签名包仍须按平台部署要求完成签名后安装。

图库、视频、取消和权限变化的详细设备场景见[实现与验收](manual-backup-implementation.md#设备验收)。模拟器结果与真机后台调度、触控和解码兼容性分别记录。
