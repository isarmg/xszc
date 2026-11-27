# Android 正式签名

应用 ID 为 `org.sarmg.ana`，PKCS#12 别名为 `ana-android-release`。
当前发布证书 SHA-256 为 `0cfc2811d48cdeab3e6d857029d879e001ab9531c06784b4d48d15a847771421`。
正式 APK 使用这一固定签名身份；Debug APK 仅用于开发验证。

GitHub 仓库 `isarmg/xszc` 的 `android-signing` 环境保存两个秘密配置：

- `ANA_ANDROID_SIGNING_PKCS12_BASE64`
- `ANA_ANDROID_SIGNING_PKCS12_PASSWORD`

签名环境仅允许审定的发行标签；具体规则以 GitHub 环境配置为准。发布前需核对对应提交、发布工作流和标签准入，不放行任意分支或 pull request。
私钥和密码不进入 Git、Release 附件或构建日志；发布任务只在临时目录生成权限受限的签名输入。

正式流水线执行 `assembleRelease`，并使用 `apksigner` 验证签名、唯一证书及以上指纹；同时检查应用 ID 和唯一 `arm64-v8a` 原生库。
Release 工作流在 Rust/合同校验、Android JVM 测试与签名构建、iOS 未签名构建及打包全部成功后创建 Release。Android 模拟器仪表测试和 iOS 单元/UI 测试由独立主 CI 执行；Release 创建成功不能代替这些原生结果，最终验收必须核对同一源码的两条工作流及下载资产。本仓库不发布服务端。iOS 制品明确标注未签名，不冒充具有 Apple provisioning 的可安装包。

签名私钥另有受限权限的离线备份。仓库管理员应将备份转存至自己的加密存储并妥善保存密码；GitHub 秘密配置不能作为唯一可恢复备份。

更名只调整 PKCS#12 条目别名和环境秘密键名；沿用同一证书、同一私钥和同一密码，证书 SHA-256 不变。工作流使用上述 `ANA_ANDROID_SIGNING_*` 秘密，并在 APK 发布前验证唯一签名证书及固定指纹。
