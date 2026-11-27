# 第一次开发运行

按[构建与测试](../development.md)准备 Rust 和目标平台工具，先运行合同检查与 Rust 测试，再构建 Android Debug 或 iOS 模拟器应用。

首次设备验证使用独立测试实例：请 ana-server 管理员准备 HTTPS 根地址、用户名和账户密码，在应用里完成[第一次备份](../getting-started.md)。本仓库的工作目录运行移动构建；服务端配置与启动按 [ana-server 文档](https://github.com/isarmg/xszs/tree/main/docs)完成。

预期结果是测试媒体从本地选择进入传输完成，并能在云端打开。Android JNI 或 iOS 链接错误先检查原生库构建和移动合同；登录与照片权限错误见[排查指南](../troubleshooting.md)。
