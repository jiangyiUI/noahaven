<div align="center">

[简体中文](README.md) | **English**

</div>

<p align="center">
  <img src="logo.png" alt="Noa Haven" width="192">
</p>

<h1 align="center">Noa Haven</h1>

<p align="center">
  A native Android local media hub covering video, music, images, comics, and book reading.<br>
  Forked from <a href="https://github.com/anilbeesetti/nextplayer">Next Player</a>, Material You designed, ad-free and tracking-free.
</p>

<p align="center">
  <a href="https://github.com/jiangyiUI/noahaven/releases">Releases</a>
  ·
  <a href="https://github.com/anilbeesetti/nextplayer">Upstream</a>
  ·
  Package <code>dev.jiangyiui.noaplayer</code>
  ·
  <a href="LICENSE">GPL v3.0</a>
</p>

## Features

### 📚 Bookshelf & Reading

- Local bookshelf: scans **TXT / EPUB** under whitelisted folders, with group management (create, move, rename, delete groups)
- Reading status: **Unread / Reading / Finished**, filterable from the status bar
- Long-press multi-select on books: **Mark as read / unread**, rename, move to another group, or clear records (removes the bookshelf entry only, never the file on disk)
- In-shelf search and global book search; search results support the same long-press multi-select actions
- Reader: custom themes, reading settings (font size / line height / margins / background), automatic progress & last-read memory, and bookmark management
- Automatic chapter detection for TXT (built-in rules); EPUB cover and table-of-contents parsing

### 🎬 Video & Playback

- Video library: **tree / folder / file** browsing modes, with grid / list / grid-of-nine layouts
- Media3-based player: audio & subtitle track selection, external subtitles, playback speed, gestures for brightness (left) and volume (right)
- Zoom & aspect ratio (fit / stretch / crop / 100%), volume boost, playback controls lock
- Picture-in-picture, background playback, playlists, and playback history

### 🖼️ Images & Comics

- Full-screen image viewer: swipe to page, pinch to zoom, double-tap to magnify, drag to pan
- Images / Comics dual tabs, **long-press drag to reorder and the order is remembered**
- Comic library scans local **CBZ / ZIP** archives
- Comic reader: paging & zooming, wide images shown half-width by default, swipe past the edge to turn pages, inertial fling, and a snap-to-page progress bar; supports left-to-right and right-to-left reading directions

### 🎵 Music

- Music library browsing and playback (Media3), sharing the playlist & history system with video

### ⚙️ General

- Bottom bar tabs **reorderable by dragging** (the "More" tab is pinned to the end)
- **Network storage**: SMB / FTP / SFTP / WebDAV
- Search history, favorite folders, and `.nomedia` support
- Material 3 (Material You) dynamic theming
- Android TV support
- No ads, no tracking, no excessive permissions

### 💾 Settings & Backup

- **Export / import** settings backup, covering player, reader, bookshelf, and bookmarks preferences
- Reader themes and reading preferences are centrally managed in Settings

## Building

Requires JDK 17 and the Android SDK.

```bash
./gradlew assembleDebug
```

Release builds require your own signing configuration; the repository does not ship any release keys.

## Player Intent API

Supports an MX Player–compatible VIEW intent. See [PLAYER_API.md](PLAYER_API.md) for details.

## Acknowledgements

- Upstream project: [Next Player](https://github.com/anilbeesetti/nextplayer)
- And the open-source projects listed in the README history: Findroid, Just (Video) Player, LibreTube, ReadYou, Seal, and others

## License

This project is licensed under the GNU General Public License v3.0. As a derivative work of Next Player, distribution must comply with GPL-3.0 as well.
