# 开始接入 xcsc

本页面向 Rust 产品开发者。完成后，你会得到可编译的 xcsc 调用和可检查的产品清单。准备 Rust `1.99.0`、Python 3，并按[目标平台](README.md#选择接入平台)准备原生工具链。

## 1. 添加依赖

Ana 的根 `Cargo.toml` 在 `[workspace.dependencies]` 中声明本地 `xcsc` 路径和精确版本。使用该库的工作区包通过 `xcsc.workspace = true` 继承依赖，所需功能由该包显式声明。工作区根目录的 `Cargo.lock` 记录解析结果。

普通桌面接入使用默认 features。导出移动 C ABI 时选择 `mobile-ffi`；Android JNI 选择 `jni`；Linux 离线维护选择 `offline-maintenance`。目标列表见[能力配置](configuration.md)。

## 2. 声明产品使用的能力

Ana 根目录的 `ana-client.toml` 声明移动端实际使用的能力。下面的独立示例展示一个只使用 HTTPS 投递适配器的桌面客户端清单。

```toml
format = 1
product_id = "demo-client"
source_roots = ["src"]

[foundation]
platform_generation = 1
version = "1.0.0"

[[components]]
id = "desktop"
profile = "desktop-client"
capabilities = ["https-delivery"]
```

按实现增加私有状态、队列等能力，所需字段见[配置能力](configuration.md)。清单记录实际使用情况，不会自动生成业务适配器。

## 3. 运行一个身份示例

在产品中创建 `examples/xcsc_identity.rs`，加入以下代码，再从产品根目录执行 `cargo run --locked --example xcsc_identity`：

```rust
use xcsc::runtime::{ClientIdentity, ContractId};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let identity = ClientIdentity::new(
        "demo-client",
        "device-1",
        ContractId::new("report-v1")?,
    )?;
    identity.ensure_matches(&identity.clone())?;
    println!("{}: {}", identity.product_id(), identity.instance_id());
    Ok(())
}
```

预期输出 `demo-client: device-1`。这个示例只验证本地 API 接入；身份、凭据和实际 HTTP 请求的组合见[使用公共模块](usage.md)。

## 4. 检查产品接入

在 Ana 仓库根目录执行本地来源和能力检查。

```sh
python3 scripts/check-xcsc-source.py
```

检查覆盖依赖来源、能力声明和源码使用情况。成功后运行产品自己的测试与构建，再按产品的安装流程交付。检查未通过时，按[排查问题](troubleshooting.md)定位具体字段或调用。
