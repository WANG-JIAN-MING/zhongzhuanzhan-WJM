# 额度 / QuotaDeck

Android New API 额度查询应用，当前版本 **1.0.18**（versionCode 19）。使用 Kotlin、Jetpack Compose 和 Material 3，支持 Android 8.0（API 26）及以上。

## 功能

- 底部导航：预览、额度、站点、设置。支持多个站点及系统、浅色、深色主题。
- 三种独立查询模式：令牌额度、个人中心账户余额、订阅当日 / 周期用量。
- 预览页集中显示站点额度。账户条目同一行显示名称、红色今日消耗和当前余额；卡片顶部显示刷新动画。
- 今日总消耗使用 `/api/log/self/stat?type=2`，按设备时区统计当天零点至本次查询，包含钱包消费与订阅抵扣，不等同于钱包净扣款。统计失败单独提示，不影响当前余额。
- 订阅展示基础额度、提前领取、已用与剩余、有效期及刷新时间；服务端允许时可在确认后透支次日额度，结果不明确时不自动重试。
- 支持 Neco Api 同站网页登录绑定用户 ID 和会话，亦可手工配置访问凭据。模型调用令牌通常不能用于账户和订阅查询。
- 金额按站点 quota 换算配置显示两位小数，默认 500000 quota = 1 USD；可从 `/api/status` 读取换算值。

## 使用与存储

从“站点”添加 HTTPS 地址并选择查询类型。Neco 快捷填充仅填写站点信息，不覆盖查询模式。网页登录后需完成绑定并保存查询。

配置和凭据使用 Android Keystore AES-GCM 加密保存，关闭备份。卸载应用会丢失配置；同签名覆盖安装可保留。网络请求禁止重定向携带凭据。仓库不包含用户凭据、签名私钥、APK 或本机 SDK 配置。

## 构建

使用 JDK 17、Android SDK 35、Build Tools 35.0.0。Gradle Wrapper 为 8.11.1，AGP 为 8.9.1，Kotlin 为 2.1.10。

设置 `ANDROID_HOME` 或通过本机 `local.properties` 配置 `sdk.dir` 后执行：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。Debug 签名由构建机器生成，另一机器构建的 APK 不保证能覆盖已安装版本。

当前源码最近验证：48 项单元测试通过；Lint 0 错误、7 条警告。尚未使用真实账户验证统计值、订阅透支及设备界面交互，构建通过不代表真实联调通过。

协议参考：[QuantumNous/new-api](https://github.com/QuantumNous/new-api) 及 Neco Api 当前公开前端。不同部署的接口和权限可能存在差异。
