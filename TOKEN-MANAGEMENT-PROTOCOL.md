# 1.0.21 令牌管理协议证据

核实站点：https://api.sbbbbbbbbb.xyz 。未使用真实管理身份、未对实际账户进行写入。

从线上 HTML 定位的当前构建为 `/assets-srcbuild-20261106225457/`，通过 `curl --compressed` 解压获取。

- 主入口：`index-X7L-wk_W.js`，路由 `/console/token` 懒加载 `index-DuGeKeAx.js`。
- 实际令牌页面：`index-DuGeKeAx.js`。
- 密钥邮箱验证：主入口中的 `TOKEN_KEY_EMAIL_VERIFICATION_REQUIRED / EXPIRED / INVALID` 及 `/api/token/key_verification/*`。
- `useTokenKeys-DfnXjNK9.js` 是另外的旧式密钥辅助组件，不作为完整密钥管理协议依据。

## 已核实请求

| 操作 | 请求与协议 |
|---|---|
| 列表 | GET `/api/token/?p=1&size=20`；前端直接读取 `data.items / total / page / page_size` |
| 名称搜索 | GET `/api/token/search?keyword=<encoded>&token=`；App 明确附加 `p / size`，每次翻页保留搜索条件 |
| 详情 | GET `/api/token/{id}`；编辑前先读取详情 |
| 创建 | POST `/api/token/`；name、remain_quota 整数 quota、unlimited_quota、expired_time 秒时间戳；永不过期为 -1 |
| 编辑 | PUT `/api/token/`；带 id，保留详情中的模型限制、IP、group、auto_groups、cross_group_retry 等字段，剔除 key 和只读统计字段 |
| 启停 | PUT `/api/token/?status_only=true`，body `{id,status}`，启用 1、停用 2 |
| 删除 | DELETE `/api/token/{id}/` |
| 完整密钥 | POST `/api/token/{id}/key`；`data.key` 或字符串 `data`；前端补齐 `sk-` 前缀 |
| 邮箱验证 | POST `/api/token/key_verification/send`；POST `/api/token/key_verification/verify` body `{code}`，检查 data.verified 后重取密钥 |

Neco 当前前端搜索函数只发送 keyword/token，而页码回调调用普通列表，存在搜索分页条件丢失风险。App 不照搬该行为，搜索每页均请求搜索接口并传递 p/size；严格校验服务端回传的 page/page_size，如站点忽略参数将停止管理并提示不兼容，不展示伪分页。

补充核对 QuantumNous/new-api main 的 `controller/token.go`：SearchTokens 使用 common.GetPageQuery 和 SearchUserTokens 的 offset/limit，回传 total/items；GetAllTokens 同样回传分页结构。该补充仅支持分页设计；Neco 请求路径、字段、邮箱验证以当前线上前端为准。没有登录身份，无法证明受保护 Neco 搜索端点的实时返回完全符合上游，因此运行时强校验保留。

## 身份与秘密

复用 Site 中 account/subscription 配置的 Cookie、管理 Bearer 和 New-Api-User。普通模型额度查询配置不自动升级为管理身份；sk- 凭据不发往管理接口，即使配置中有有效 Cookie 也仅发 Cookie 管理身份。管理请求要求 HTTPS、禁止重定向、限制响应大小、校验请求头换行。

列表仅保留无密钥的 ManagedToken。完整密钥按主动查看请求，仅在当前 Compose 临时状态中展示；不保存到 savedInstanceState，不输出服务端原文错误或秘密日志。复制使用 Compose LocalClipboardManager；主动加入额度查询时沿用 Android Keystore 加密存储。关闭对话框、刷新或切换页面后隐藏密钥。

创建不自动重试。写入成功、失败或结果不明确均刷新列表和已有额度数据；创建后清空名称过滤并刷新全部列表第一页。删除空末页自动回退到新的末页。刷新失败时禁用管理按钮，防止在陈旧列表上继续写入。
