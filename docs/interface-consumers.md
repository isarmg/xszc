# 移动图库接口消费范围

Android 与 iOS 当前都消费云端时间线、详情、筛选、相册、收藏/归档、回收站、单项标签、永久删除、
重复项分组、资源预览和视频分段读取。危险删除只在回收站资产详情出现，并要求二次确认。

每项媒体最多关联 16 个标签；先按精确名称复用账户已有标签，再关联当前媒体。已有关联可幂等重试，
历史超过上限的媒体仍可查看和移除标签。客户端预检与提示不能替代配套服务端的事务内上限校验。

移动端没有接入以下账户自动化接口：

- `PUT /v1/tags/{tag_id}/assets`：这是全量成员替换，保留给显式批处理工具；移动端使用单资产
  `POST/DELETE`，避免一次误选覆盖整个标签成员集合。
- `/v1/api-keys`：API Key 用于额外客户端和自动化，原值只在创建时返回；不由日常图库代管。
- `/v1/audit-events`：账户审计由持账户凭据的诊断工具分页读取，不与服务端管理员审计混合。

这些边界与服务端的 [接口与消费者边界](https://github.com/isarmg/xszs/blob/main/docs/interface-consumers.md)
保持一致。移动端不得使用管理员 Cookie，也不得为了显示账户接口而持有服务端管理员密码。

### 上传操作标识

Android 与 iOS 在 `POST /v1/uploads` 请求中，将持久化的
`PreparedJob.generation_id` UUID 作为必需的 `X-Upload-Operation` 请求头发送。
header 名与非 nil UUID 校验由 `xszs-protocol::UPLOAD_OPERATION_HEADER` / `parse_upload_operation`
定义。Rust FFI `next` 在响应增加 `upload_operation_header`，两端请求构造直接消费该运行时字段；
该字段不写入持久化 `PreparedJob`，不改变已有任务和分片。缺失字段时原生端拒绝该响应。
`CreateUploadRequest` 请求体保持不变。HTTP 重试和进程重启必须复用该 UUID，不能每次请求生成新标识。

预处理分块丢失时，队列保留原上传清单，并等待重新导出源文件。重建时沿用原分块边界，只有完整上传
请求完全相同时才复用原 generation UUID；内容或元数据变化会生成新 UUID，显式缩略图刷新也会开启
新 generation。因此，缓存丢失不会把旧上传重试变成新操作，覆盖已经完成的新版本资源。
