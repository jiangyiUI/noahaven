<div align="center">

**简体中文** | [English](README.en.md)

</div>

<p align="center">
  <img src="logo.png" alt="Noa Haven" width="192">
</p>

<h1 align="center">Noa Haven</h1>

<p align="center">
  一款 Android 原生的本地媒体中心，涵盖视频、音乐、图片、漫画与书籍阅读。<br>
  基于 <a href="https://github.com/anilbeesetti/nextplayer">Next Player</a> 修改而来，Material You 设计，无广告、无追踪。
</p>

<p align="center">
  <a href="https://github.com/jiangyiUI/noahaven/releases">Releases</a>
  ·
  <a href="https://github.com/anilbeesetti/nextplayer">上游项目</a>
  ·
  包名 <code>dev.jiangyiui.noaplayer</code>
  ·
  <a href="LICENSE">GPL v3.0</a>
</p>

## 功能

### 📚 书架与阅读

- 本地书架：扫描白名单目录下的 **TXT / EPUB**，支持分组管理（新建、移动、重命名、删除分组）
- 阅读状态：**未读 / 阅读中 / 读完** 三态，状态栏可按状态筛选
- 书籍长按多选：**标记已读 / 未读**、重命名、移动到其它分组、清除记录（仅移除书架记录，不删除磁盘文件）
- 书架内搜索与全局书籍搜索，搜索结果同样支持长按多选操作
- 阅读器：自定义主题、字号 / 行距 / 页边距 / 背景等阅读设置，进度与最近阅读自动记忆，书签管理
- TXT 自动章节识别（内置规则），EPUB 解析封面与目录

### 🎬 视频与播放

- 视频库：**树形 / 文件夹 / 文件** 三种浏览模式，网格 / 列表 / 九宫格布局
- 基于 Media3 的播放器：音轨与字幕选择、外部字幕、播放速度、手势调节亮度（左）与音量（右）
- 画面缩放与比例（适应 / 拉伸 / 裁剪 / 100%）、音量增强、播放控制锁定
- 画中画、后台播放、播放列表、播放历史

### 🖼️ 图片与漫画

- 图片库全屏查看：左右翻页、双指缩放、双击放大、拖动
- 图片 / 漫画双 tab，**长按拖动换序并记住顺序**
- 漫画库扫描本地 **CBZ / ZIP** 压缩包
- 漫画阅读器：翻页与缩放、宽图默认半幅显示、滑到边界再划翻页、松手惯性滑动、底部逐页吸附进度条，支持左 / 右阅读方向切换

### 🎵 音乐

- 音乐库浏览与播放（Media3），与视频共用播放列表与历史体系

### ⚙️ 通用

- 底栏 tab **可拖动自定义排序**（「更多」固定在最后）
- **网络存储**：SMB / FTP / SFTP / WebDAV
- 搜索历史、常用文件夹、支持忽略 `.nomedia`
- Material 3（Material You）动态主题
- Android TV 支持
- 无广告、无追踪、不过度申请权限

### 💾 设置与备份

- 设置**导出 / 导入备份**，覆盖播放器、阅读器、书架与书签等偏好
- 阅读器主题与阅读偏好可在设置页集中管理

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
