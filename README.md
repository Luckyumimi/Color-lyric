# TerraMoor

TerraMoor 是一个 ColorOS 17 的 LSPosed 模块，在系统原生流体云中显示双语歌词，支持实时调参、无歌词回退和模糊封面背景。

可在「调整 → 胶囊元素」分别隐藏歌曲封面和律动频段，歌词会填充腾出的空间，胶囊总宽度保持不变。

## 预览

![原生胶囊动态预览](docs/previews/capsule-preview.gif)

| 应用内预览 | 系统流体云 |
| --- | --- |
| ![应用内胶囊](docs/previews/app-capsule.png) | ![系统媒体胶囊](docs/previews/system-capsule.png) |

## 安装

1. 在 Releases 下载 APK。应用可安装于 Android 14 及以上。歌词功能需要支持 API 102 的 LSPosed，以及原生媒体胶囊接口兼容的 ColorOS 系统。
2. 启用 TerraMoor，作用域勾选「系统界面」，重启 SystemUI 或手机后打开 TerraMoor。应用内的重启按钮需要 Root 授权。
3. 在首页确认 LSPosed 已勾选「系统界面」、已启用流体云歌词、ColorOS Live Lyrics Bridge 和通用播放器 Provider 均已安装，再到「调整」设置显示效果。播放时可按播放器选择专用或通用 Provider。
4. 旧调试包可在「关于」导出配置，正式包导入后停用旧模块。同一时间启用一个 TerraMoor 包。

## 模块结构

源码在 `app/src/main/java/shop/terramoor/github/io/`。

| 目录 | 职责 |
| --- | --- |
| `settings/` | COUI 首页、调整页和关于页，Provider 状态与入口，后台串行远程配置写入，预览和 JSON 备份。 |
| `lyrics/` | 读取媒体会话的 `lyricInfo`，解析 LRC 和可匹配的 TTML 句尾，计算下一句预显和横向滚动进度。 |
| `systemui/` | 挂载媒体胶囊 Hook，校验系统接口、媒体会话及视图所有权。沿用系统动画绘制歌词与背景，停用时恢复原生胶囊。 |
| `app/src/main/java/io/github/libxposed/service/` | API 102 设置服务入口校验。 |
| `app/src/main/resources/META-INF/xposed/` | 模块入口和静态 `com.android.systemui` 作用域。 |
| `app/src/test/`、`app/src/androidTest/` | JVM 单测及设备测试。 |

播放器或 Provider 把歌词写入媒体会话。TerraMoor 从当前胶囊关联的 Token 获取歌词。设置经 LSPosed Remote Preferences 传到 SystemUI。首页仅检查已知的两个 Provider 包名，在「关于」点击「检查更新」会请求 GitHub API，显示版本状态或连接失败。

## 开发与反馈

安装 Android SDK 37、JDK 25 后，用 `./gradlew :app:assembleLocalDebug :app:testLocalDebugUnitTest` 构建和测试。正式包使用 `:app:assemblePublicRelease`，签名及提交步骤见 [发布指南](docs/lsposed/submission.md)，维护约定见 [AGENTS.md](AGENTS.md)。

遇到歌词不显示或流体云异常时，重启手机，播放一首确认有歌词的歌曲并复现问题，然后在「关于 → 导出诊断日志」保存 TXT。报告包含设备和系统版本、已安装 APK 的 SHA-256、LSPosed 中实际载入的模块版本、当前配置，以及 Hook、媒体匹配、胶囊视图、歌词解析和播放调度记录。日志保留最近 4096 条，省略歌名、歌词正文及会话令牌。

反馈时请同时提供重启后的完整 LSPosed 日志、播放器及版本、复现时间和歌曲、Provider 是否能看到歌词、截图中的歌词来源，以及星流或其他修改系统界面的模块启用情况。应用无法直接读取完整 LSPosed 日志或可靠列出其他模块，需手动提供。Issues 可提交反馈；在「关于」可复制 QQ 群号 `1102727864`。

日语预览包含《ツクヨミステップ》的封面与短句。第三方素材的权利归原权利人。

[ColorOS Live Lyrics Bridge 图标](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge/blob/a51c946d1c66f02a6a4a96dc0f568e387fe05844/app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png)和本项目代码均使用 Apache-2.0。
