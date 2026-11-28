# 仓库与发布来源

Ana 导入的 xcsc 源码提交见[客户端基础库来源](../ORIGIN.md)。导入前，
xcsc 仓库独立构建和发布，并核对 Git 标签与提交后生成带校验和的源码归档。

历史 XCSC 发布记录位于 `docs/releases/`。Ana 当前从 `crates/client-foundation`
编译 xcsc，版本和依赖解析由 Ana 工作区管理。改动在 Ana 仓库内验证并随客户端发布。
