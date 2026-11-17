# Noa Player

Noa Player 是一个 Android 原生本地媒体播放器，基于 [Next Player](https://github.com/anilbeesetti/nextplayer) 修改而来。
除了视频播放，还提供音乐库、图片库、漫画库、播放列表和网络存储（SMB / FTP / SFTP / WebDAV）支持。

- 包名：`dev.jiangyiui.noaplayer`
- 许可证：GNU GPL v3.0（见 [LICENSE](LICENSE)）
- 上游项目：[anilbeesetti/nextplayer](https://github.com/anilbeesetti/nextplayer)

## 功能

- 视频、音乐、图片三个独立媒体库，支持树形目录 / 文件夹 / 文件三种浏览模式
- 网格、列表、九宫格布局与快捷设置，搜索历史与常用文件夹
- 图片全屏查看：左右翻页、双指缩放、双击放大、拖动
- 漫画库：图片页顶部「图片 / 漫画」tab 切换（长按拖动换序并记住顺序），扫描本地 CBZ / ZIP 压缩包
- 漫画阅读器：左右翻页与双指缩放，宽图默认半幅显示，滑到边界再划翻页，松手惯性滑动，底部逐页吸附进度条，支持左 / 右阅读方向切换
- 播放列表、播放历史（含漫画阅读记录）、Media3 播放器、后台播放、画中画
- 音轨与字幕选择、外部字幕、播放速度、手势调节亮度与音量
- Android TV 支持
- 网络存储（SMB / FTP / SFTP / WebDAV）
- 支持忽略 .nomedia

## 漫画

把 `.cbz` / `.zip` 漫画压缩包放到媒体库扫描的目录里，在「图片」页顶部切到「漫画」tab 即可浏览。

- 按压缩包内的文件名顺序作为阅读顺序
- 自动记录每本的阅读进度，可在「更多 - 历史记录」中与视频、音乐一起查看并继续阅读
- 阅读器：左右滑动翻页、双指缩放，滑到边缘后再划一次翻页；底部进度条按页吸附，可拖动跳页

## 构建

需要 JDK 17 和 Android SDK。

```bash
./gradlew assembleDebug
```

Release 构建需要自行配置签名，仓库不包含任何发布密钥。

## 播放器 Intent API

支持 MX Player 兼容的 VIEW intent，详见 [PLAYER_API.md](PLAYER_API.md)。

## 致谢

- 上游项目：[Next Player](https://github.com/anilbeesetti/nextplayer)
- 以及 README 历史中列出的 Findroid、Just (Video) Player、LibreTube、ReadYou、Seal 等开源项目

## 许可证

本项目遵循 GNU General Public License v3.0。作为 Next Player 的衍生作品，分发时需同样遵守 GPL-3.0。
