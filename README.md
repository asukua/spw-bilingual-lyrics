# spw 双语歌词顺序

[Steam 创意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3822500887) · [下载插件](https://github.com/asukua/spw-bilingual-lyrics/releases/latest) · [源码](https://github.com/asukua/spw-bilingual-lyrics) · MIT 开源

这个插件可以调整椒盐音乐桌面版里的双语歌词顺序。想把原文放在上面，还是先看译文，都可以在模组配置里选。

## 安装与使用

1. 从[发布页](https://github.com/asukua/spw-bilingual-lyrics/releases/latest)下载 `.spmod`，在椒盐音乐「创意工坊 → 模组管理」中导入并启用。
2. 在播放页「…」菜单切换原文或译文在上；也可在模组配置中调整歌词来源、识别方式和输出顺序。

菜单切换需要「修改应用行为」权限，不需要曲库读取权限。该功能以实际接口判断兼容性，验证目标为 Steam 版 1.19.0 和 1.19.2；实际窗口效果仍需验收，接口不兼容时仍可使用配置页。

## 支持范围

- 支持常见 LRC / SPL 双语歌词、逐字时间及符合条件的同行双语拆分。
- 支持内嵌和同名旁挂歌词；默认自动识别中文译文，也可手动指定输入顺序。
- 无法安全识别的歌词会交回播放器；纯汉字日文、混唱及多语言歌词可能需要手动调整。

[歌词格式、限制与维护说明](docs/lyrics-details.md) · [版本更新](release-notes.md)

## 构建

使用 JDK 25，在项目目录运行：

```powershell
.\gradlew.bat test plugin
```

插件输出到 `build/libs/`。项目采用 MIT，第三方库许可见 `third-party/`。
