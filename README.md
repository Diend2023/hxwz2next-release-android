# hxwz2next-release-android

基于幻想纹章新篇（AnimeBattleNext，Electron / OpenFL HTML5 版）热更新方案的安卓壳工程。整体思路与 [hxwz4-release-android](https://github.com/Diend2023/hxwz4-release-android) 一致，但热更新协议与游戏端 `GameResourceUpdater` 对齐，实现细节大不相同。

- 更新源：CNB 仓库 raw —— `https://cnb.cool/anime-battle-next/hxwz2-release/-/git/raw/main/`
- 更新清单：`md5s.json`（文件级 MD5：`{仓库相对路径: md5}`，键为仓库键而非本地路径）
- 机制：启动 → 更新界面（初始化不可跳过、热更新可跳过）→ WebView（WebViewAssetLoader 加载外部私有目录 `Android/data/com.hxwz2next.app/files/web`）

## 热更新特殊点

- **清单键不是本地路径**：`md5s.json` 的键是热更仓库的相对路径，落盘前需经映射（规则见下），与游戏端 `GameResourceUpdater.keyToLocalPath` 完全一致。
- **`app.zip` 框架包**：清单中的 `app.zip` 键代表游戏框架（AnimeBattleNext.js、assets/、editerAssets/ 等），更新时整体下载 → 覆盖解压到 web 根目录 → 删除 zip；APK 基线中同样内置一份 `app.zip`，首次启动优先解压生成框架。
- **豁免文件**：`index.html`、`favicon.png` 永不参与热更，始终以 APK 内置版为准（每次启动同步）。
- **游戏端更新器自禁用**：游戏在非 Electron 环境（WebView）会跳过自身热更新，全部更新逻辑由本壳原生实现。
- **本地清单兼容游戏端**：落盘的 `md5s.json` 键保持远程键原样（含 `app.zip` 键），游戏读取它做版本指纹。

**路径映射**（`UpdateManager.keyToLocalPath`）：

```
abn/xxx     → assets/roles/xxx
icons/xxx   → assets/icons/xxx
maps/xxx    → assets/map/xxx     （注意单数）
sounds/xxx  → assets/sounds/xxx
app.zip     → 下载后整体覆盖解压到 web 根目录，随后删除 zip
```

## 目录结构

```
hxwz2next-release-android/
├── repo/                       # 热更资源仓库 clone（需手动 clone，见下）
│   └── hxwz2-release/          #   git clone --depth=1 https://cnb.cool/anime-battle-next/hxwz2-release.git
├── app/                        # Android 壳工程
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/www/         # 基线资源（由 tools/sync_baseline.ps1 生成，不入库）
│       ├── java/com/hxwz2next/app/
│       │   ├── MainActivity.kt      # 横屏+沉浸式全屏，更新界面 → WebView
│       │   ├── UpdateManager.kt     # 热更新：基线初始化/路径映射/app.zip 解压/增量下载
│       │   ├── ZipUtils.kt          # ZIP 解压（覆盖式 + Zip Slip 防护 + 原子替换）
│       │   └── FileHasher.kt        # MD5 工具
│       └── res/
├── tools/
│   └── sync_baseline.ps1       # 仓库资源 + other/ → app/assets/www（按映射复制）
├── other/                      # 热更仓库不提供但游戏必需的文件（随 APK 内置，不参与热更）
│   ├── index.html              # 入口（保持原版全屏自适应）
│   ├── favicon.png             # index.html 引用
│   └── assets/sounds/          # 12 个 UI/战斗音效（本地游戏有、app.zip 与热更仓库均无）
├── gradle/                     # Gradle wrapper
├── settings.gradle.kts
└── ...
```

> `other/` 的取舍依据：本地 Electron 版 `resources/app` 中，`AnimeBattleNext.js`、`assets/`、`editerAssets/`、`ElectronSetup.js`、`ElectronTools.js` 均由 `app.zip` 覆盖；`lib/`（howler/pako/FileSaver）、`manifest/default.json`、`zip/`（unzip 工具）、`DEMO/`、`package.json`、`icon.*` 等经排查在 WebView 运行时零引用，无需打入；`maps/`、`sounds/`（地图 BGM）由热更仓库提供。仅 `index.html`、`favicon.png` 与 12 个 UI 音效是缺口，放入 `other/`。

## 一、准备（一次性）

1. 环境要求：JDK 17+、Android SDK（compileSdk 34）、`local.properties` 已指向 SDK。
2. Clone 热更资源仓库：

```bat
mkdir repo
git clone --depth=1 https://cnb.cool/anime-battle-next/hxwz2-release.git repo/hxwz2-release
```

3. 生成 APK 基线资源：

```bat
powershell -ExecutionPolicy Bypass -File tools\sync_baseline.ps1
```

> 说明：
> - 脚本按路径映射复制 `abn/`→`assets/roles/`、`icons/`→`assets/icons/`、`maps/`→`assets/map/`、`sounds/`→`assets/sounds/`，并复制随包 `app.zip` 与 `md5s.json`（强制无 BOM，PowerShell 5.1 需用 `UTF8Encoding($false)`，否则客户端 JSONObject 解析失败）。
> - **脚本自身需带 UTF-8 BOM**（PowerShell 5.1 才能正确解析中文脚本；仅影响脚本文件，不影响产物）。
> - `repo/hxwz2-release` 的 `app.zip`、`md5s.json` 与资源目录必须同源于同一提交（仓库 git hooks 会在 commit 时自动重算清单）。

### index.html 移动端适配（最终方案：保持原版全屏自适应）

```html
<style>
    html,body { margin: 0; padding: 0; height: 100%; overflow: hidden; }
    #openfl-content { background: #000000; width: 100%; height: 100%; }
</style>
<script>
    lime.embed ("AnimeBattleNext", "openfl-content", 0, 0, { parameters: {} });
</script>
```

`lime.embed` 使用 `0, 0`（跟随窗口尺寸），`#openfl-content` 100% 填充。HXMAKER 引擎自带视口自适应（`stage.updateLayout`，真机日志可见 `[HXMAKER] size changed, 1920, 1080, scaleFactor 0.5`），无需外层缩放 JS。

踩坑记录（适配探索过程）：

- 曾实施 hxwz4 同构方案（固定 `960x540` + `allowHighDPI: true` + 容器等比缩放居中 JS），画面可完整显示，最终仍回归原版 `0, 0` 全屏自适应，依赖引擎自身布局能力
- WebView 布局视口实测（dpr=3 设备）：约 770x360，与原版 `devicePixelRatio > 2` viewport 修改脚本的预期不符；当前版本该脚本仅保留空判断（无可执行语句），等效于纯原版
- 备用方案要点（若未来需要固定尺寸）：CSS 尺寸必须带单位（`960px`，裸数字会被浏览器忽略）；`scale()` 必须配合 `transform-origin: 0 0`，否则画面错位到右下角；需移除 viewport 的 dpr 改写逻辑

## 二、构建 APK

```bat
gradlew.bat assembleDebug          # 调试包
gradlew.bat assembleRelease        # 发布包（当前未混淆）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 530MB，含基线）

## 三、热更新行为说明

| 场景 | 行为 |
| ---- | ---- |
| 首次启动 | 清理残留临时文件 → 将 APK 内置基线复制到外部私有目录（`Android/data/com.hxwz2next.app/files/web/`）→ **优先解压随包 `app.zip`** 生成游戏框架（AnimeBattleNext.js、assets/、editerAssets/ 等）并删除 zip → `index.html` **最后落盘**作为“基线完整”标志（中断时它不存在 → 下次重新完整初始化）；随包 `md5s.json` 一并就位。初始化不可跳过 |
| 常规启动 | 同步豁免文件为 APK 内置版 → 清理残留临时文件 → 拉取远程 `md5s.json` → 差异计算 → 增量下载（6 并发、失败重试 2 次），热更新阶段可跳过 |
| 远程清单 MD5 过期 | 下载内容 MD5 与声明值不符、但与本地已有文件一致时（repo 改资源忘重新生成清单），按声明值收敛清单条目、不重复替换文件，避免每次启动重复下载 |
| 清单键比对 | 经 `keyToLocalPath` 映射后检查文件存在性 + MD5；`app.zip` 键只比对清单 MD5（本地不留存 zip，与游戏端一致） |
| `app.zip` 有变化 | 下载到临时文件 → MD5 校验 → 覆盖解压到 web 根（`unzip -o` 语义，维护框架结构）→ 删除 zip → 立即更新本地清单该键；**失败保留旧框架继续**，下次启动重试 |
| 双方 MD5 不同 | 下载新版本**覆盖**旧文件（先写 `.tmp` 校验 MD5 后原子替换） |
| 远程已移除、本地仍存在 | 一律保留，不做删除 |
| 豁免文件 | `index.html`、`favicon.png` 不下载/不覆盖/不删除；且每次启动同步为 APK 内置版（APK 升级即生效） |
| 断网/清单拉取失败 | 更新界面提示"重试 / 跳过"，跳过则用本地版本直接进游戏 |
| 本地清单格式 | 与游戏端 `GameResourceUpdater.saveLocalMd5s` 兼容：键保持远程键原样（含 `app.zip` 键，游戏读取它做版本指纹） |
| 更新源 | `BASE_URL` 定义于 `UpdateManager.kt`，如需切换镜像直接修改 |
| 资源目录 | `UpdateManager.resolveWebDir()`：优先 `getExternalFilesDir(null)/web`（无需存储权限，卸载自动清除），外部存储不可用时回退内部 `filesDir/web` |

## 四、关键实现点

- 横屏 + 沉浸式全屏：`MainActivity.enterFullscreen()`（`WindowInsetsControllerCompat`）。
- **WebView 焦点管理（键盘唤起）**：`MainActivity.dispatchTouchEvent` 触摸时若 WebView 无焦点则 `requestFocus`。游戏输入框为 canvas 自绘，Lime 引擎以 20ms 延迟程序性 focus 隐藏 input，只有 WebView 已是窗口焦点视图（served view）时该 focus 触发的 `showSoftInput` 才会被系统接受（否则 `PHASE_CLIENT_VIEW_SERVED` 失败，软键盘无法唤起）。输入会话中点击非输入区结束会话的行为由游戏框架端处理。
- WebView：`WebViewAssetLoader` 以 `https://appassets.androidplatform.net/web/index.html` 虚拟域加载**外部私有目录**资源，规避 `file://` 的 fetch/XHR 跨域限制；开启 JS、DOM Storage、媒体自动播放；`InternalStoragePathHandler` 仅支持 `filesDir` 下目录，故使用自定义 `PathHandler`（含路径穿越校验 + MIME 推断）。
- **子资源 404 不降级**：`onReceivedError` 仅在 `request.isForMainFrame` 时降级 `file://` 兜底加载——子资源个别缺失（如音效）不应触发整页重载。
- **`app.zip` 解压**（`ZipUtils.kt`）：覆盖式解压（对应游戏端 `unzip -o` 语义，不删除框架旧文件）、Zip Slip 防护、临时文件原子替换；支持直接读 APK assets 流（首启解压无需中间落盘）。
- 更新：OkHttp + 协程 + 信号量并发（6），文件先写 `.tmp`、MD5 校验通过后原子改名，避免半截文件；下载 URL 按路径段分别 URL 编码（兼容中文文件名）；`readTimeout` 180s（单文件最大 71MB）。
- 清单可靠性：`md5s.json` 写回采用**原子写**（先写 `.tmp` 再改名，防写一半断电损坏清单触发全量重下）；启动时清理中断残留的 `.tmpN` / `.tmp` / `.ztmp`；用户跳过更新时也收敛已成功文件的清单条目，避免下次重复下载。
