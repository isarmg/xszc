# 客户端基础库

ana 的队列、私有状态、凭据和移动端 FFI 使用仓库内的 [XCSC 源码](../crates/client-foundation/ORIGIN.md)。根 Cargo 工作区和 Cargo.lock 共同描述实际构建的依赖。

`mobile-ffi` 提供 C ABI，`jni` 在 Android 上提供 JNI 入口。修改接口时同步检查 Kotlin、Swift 和 C 头文件。运行 `python3 scripts/check-xcsc-source.py` 验证本地依赖与 FFI 边界。运行 `python3 scripts/check-mobile-header.py` 检查 C 头文件。
