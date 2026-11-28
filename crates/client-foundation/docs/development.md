# 构建与测试

准备 Rust `1.99.0`、Python 3 和目标操作系统的原生 C/C++ 编译工具链。在 Ana 仓库根目录运行以下检查和构建命令。

```sh
python3 scripts/check-xcsc-source.py
cargo fmt --all -- --check
cargo clippy --locked -p xcsc --all-targets --all-features -- -D warnings
cargo doc --locked -p xcsc --all-features --no-deps
```

Python 检查产品来源和能力。GitHub Actions 执行工作区测试和平台验证。API 文档写入 `target/doc/xcsc/`。Linux 的 `--all-features` 包含离线 SQLite，其他平台保持各自模块边界。

## 选择专项验证

| 改动 | 补充验证 |
|---|---|
| 私有文件、ACL、锁、服务管理 | 在 Linux、Windows 和 macOS 对应原生 CI 执行 |
| C ABI/JNI、句柄、字符串 | 真实 C/JVM 及产品 Android/iOS 测试 |
| 能力清单或消费检查 | Python 工具测试和一个实际消费者的检查 |
| 产品协议适配器 | 在产品仓库验证协议与业务状态 |

按目标平台查看工具链、原生构建与专项排障：

- [Linux](platforms/linux.md)：GNU x86_64 与离线维护测试
- [Windows](platforms/windows.md)：MSVC、ACL 与服务身份
- [macOS](platforms/macos.md)：物理临时路径及 Apple 沙箱回归
- [Android](platforms/android.md)：移动目标与 Java 17 JVM 专项测试
- [iOS](platforms/ios.md)：实机/模拟器目标与 Swift/C ABI 集成

交叉检查验证目标可编译，文件权限、设备运行和产品安装须在对应原生环境验证。

## 发布和产品更新

更新公共 API 时，同步调整对应文档、能力清单及受影响的测试。依赖升级后核对工作区锁文件并通过消费检查和产品测试。导入记录见[发布来源](split-origin.md)，原生调用审查见[参考索引](reference/README.md)。
