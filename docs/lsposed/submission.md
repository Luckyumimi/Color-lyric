# LSPosed 首发准备

已核对 [官方提交说明](https://github.com/Xposed-Modules-Repo/submission) 和 [审核机器人源码](https://github.com/Xposed-Modules-Repo/submission/blob/master/src/index.ts)。

- 正式包 `io.github.luckyumimi.terramoor` 使用登录账号 `Luckyumimi` 对应的前缀。新 Issue 的标题为 `[submission] io.github.luckyumimi.terramoor`。机器人审批后会创建同名仓库并发送维护邀请。
- 在网页中将新仓库的描述设为 `TerraMoor`。上传同目录的 `SUMMARY` 与 `README.md`，不必上传源码。支持链接可在确定后填入仓库主页字段。
- 创建 Release 时同时上传已签名 APK。标题 `1.0`，标签 `1-1.0`，更新说明使用 `release-1.0.md`。仅替换已发布的附件不会触发标签更新。官方建议在全部要求满足三个工作日后仍未收录时反馈。

## 构建和签名

```powershell
.\gradlew.bat :app:assemblePublicRelease
```

产物为 `app/build/outputs/apk/public/release/app-public-release-unsigned.apk`。使用维护者选定的正式密钥签名。`apksigner` 默认提示输入密码，无需在命令或聊天中填写。已核对未签名产物的对齐，可直接签名。

```powershell
apksigner sign --ks '<正式密钥文件>' --ks-key-alias '<别名>' --out TerraMoor-1.0.apk app/build/outputs/apk/public/release/app-public-release-unsigned.apk
apksigner verify --verbose --print-certs TerraMoor-1.0.apk
```

锁定首发签名后，后续版本持续用同一签名密钥更新。先在 PKX110 上安装签名包，用新作用域验证播放、实时设置和停用恢复。本地调试包沿用 `shop.terramoor.github.io`，可通过应用内 JSON 导出和导入迁移配置。同一时段只启用一个 TerraMoor 包。正式 APK 保留用户指定的日语示例，公开附件上传前确认封面和歌词示例的分发权限。
