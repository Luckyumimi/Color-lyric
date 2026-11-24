# LSPosed 首发准备

已核对 [官方提交说明](https://github.com/Xposed-Modules-Repo/submission) 和 [审核机器人源码](https://github.com/Xposed-Modules-Repo/submission/blob/master/src/index.ts)。

- 正式包 `io.github.luckyumimi.terramoor` 使用登录账号 `Luckyumimi` 对应的前缀。新 Issue 的标题为 `[submission] io.github.luckyumimi.terramoor`。机器人审批后会创建同名仓库并发送维护邀请。
- 发布仓库的描述为 `Color lyric · 流体云歌词`，仓库名仍是 `io.github.luckyumimi.terramoor`。上传同目录的 `SUMMARY` 与 `README.md`，不必上传源码。仓库主页指向 `Luckyumimi/Color-lyric`。
- 创建 Release 时同时上传已签名 APK。标题 `1.0`，标签 `1-1.0`，更新说明使用 `release-1.0.md`。仅替换已发布的附件不会触发标签更新。官方建议在全部要求满足三个工作日后仍未收录时反馈。

## 构建和签名

```powershell
.\gradlew.bat :app:assemblePublicRelease
```

产物为 `app/build/outputs/apk/public/release/app-public-release-unsigned.apk`。正式包统一用签名脚本。密钥固定为 `D:\Onedrive\文档\androidkey\DebPlus-release.jks`（别名 `debplus`），凭据固定从同目录的 `DebPlus-release-credentials.txt` 读取。签名后直接比对该目录中的公开证书 `Color-lyric-DebPlus-release-signer.pem`。缺文件、签名失败或证书不符时停止，不交付 APK，也不改用其他密钥。

```powershell
.\scripts\Sign-ColorLyricRelease.ps1 -BuildTools 'E:\AndroidStudio\sdk\build-tools\37.0.0' -OutputApk '.\build\apk\Color-lyric-release.apk'
.\scripts\Sign-ColorLyricRelease.ps1 -BuildTools 'E:\AndroidStudio\sdk\build-tools\37.0.0' -VerifyApk '.\build\apk\Color-lyric-release.apk'
```

GitHub 正式发布的 1.9.0 使用不同的证书，不能直接覆盖安装用 DebPlus 密钥签名的版本。需要迁移时，先通过应用内 JSON 导出设置，再卸载旧版、安装新版并导入。先在 PKX110 上验证播放、实时设置和停用恢复。本地调试包沿用 `shop.terramoor.github.io`，同一时段只启用一个模块包。正式 APK 保留用户指定的日语示例，公开附件上传前确认封面和歌词示例的分发权限。
