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
│       │   ├── MainActivity.kt      # 横屏+沉浸式全屏，更新界面 → WebView，WebView 焦点管理
│       │   ├── UpdateManager.kt     # 热更新：基线初始化/路径映射/app.zip 解压/增量下载
│       │   ├── ZipUtils.kt          # ZIP 解压（覆盖式 + Zip Slip 防护 + 原子替换）
│       │   └── FileHasher.kt        # MD5 工具
│       └── res/
├── tools/
│   └── sync_baseline.ps1       # 仓库资源 + other/ → app/assets/www（按映射复制）
├── other/                      # 热更仓库不提供但游戏必需的文件（随 APK 内置，不参与热更）
│   ├── index.html              # 入口（含移动端适配）
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

### index.html 移动端适配（与 hxwz4 同构，真机验证）

```html
<style>
    html,body { margin: 0; padding: 0; height: 100%; overflow: hidden; background: #000; }
    #openfl-content { background: #000000; width: 960px; height: 540px; transform-origin: 0 0; }
</style>
<script>
    lime.embed ("AnimeBattleNext", "openfl-content", 960, 540, { allowHighDPI: true });
    // 容器等比缩放居中（兼容任意屏幕）
    (function() {
        var GW = 960, GH = 540;
        var ct = document.getElementById('openfl-content');
        function scale() {
            var ww = window.innerWidth, wh = window.innerHeight;
            var s = Math.min(ww / GW, wh / GH);
            ct.style.transform = 'scale(' + s + ')';
            ct.style.marginLeft = ((ww - GW * s) / 2) + 'px';
            ct.style.marginTop  = ((wh - GH * s) / 2) + 'px';
        }
        scale();
        window.addEventListener('resize', scale);
    })();
</script>
```

要点（踩坑记录）：

- **`lime.embed` 固定 `960x540` + `allowHighDPI: true`**——引擎以高分辨率 stage 渲染再缩放显示，保证清晰度
- **容器固定尺寸 + `transform-origin: 0 0`**——`scale()` 必须配合 `transform-origin:0 0`，否则画面错位到右下角
- **容器等比缩放 JS**：`Math.min(ww/GW, wh/GH)` + margin 居中 + resize 监听，任意屏幕比例完整显示不变形
- **移除原版的 `devicePixelRatio > 2` viewport 修改脚本**——WebView 布局视口与之预期不符（实测 dpr=3 时视口仅 770x360），固定尺寸无缩放 JS 会横向溢出裁切
- CSS 尺寸必须带单位（`960px`，裸数字会被浏览器忽略导致适配失效）

## 二、构建 APK

```bat
gradlew.bat assembleDebug          # 调试包
gradlew.bat assembleRelease        # 发布包（当前未混淆）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 530MB，含基线）

## 三、热更新行为说明

| 场景 | 行为 |
| ---- | ---- |
| 首次启动 | 将 APK 内置基线复制到外部私有目录（`Android/data/com.hxwz2next.app/files/web/`），随后**优先解压随包 `app.zip`** 生成游戏框架（AnimeBattleNext.js、assets/、editerAssets/ 等）并删除 zip；随包 `md5s.json` 一并就位。初始化不可跳过 |
| 常规启动 | 同步豁免文件为 APK 内置版 → 拉取远程 `md5s.json` → 差异计算 → 增量下载（6 并发、失败重试 2 次），热更新阶段可跳过 |
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

- 横屏 + 沉浸式全屏：`MainActivity.enterFullscreen()`（`WindowInsetsControllerCompat`）。**不要与主题 `windowFullscreen=true` 叠加**——双通道冲突会导致 WebView 内输入框聚焦时 IME 请求被系统拒绝（`PHASE_CLIENT_VIEW_SERVED` 失败），软键盘无法唤起。
- **软键盘（IME）唤起**：WebView 必须持有 view 焦点。`MainActivity.dispatchTouchEvent` 在触摸时 `requestFocus(FOCUS_DOWN)`，进入 WebView 时也主动请求一次；layout 中 WebView 显式声明 `focusable` / `focusableInTouchMode`；Activity 配置 `windowSoftInputMode="adjustResize"`。游戏 JS 对输入框的 `focus()` 触发的 `showSoftInput` 才不会因 "view is not served" 被拒。
- WebView：`WebViewAssetLoader` 以 `https://appassets.androidplatform.net/web/index.html` 虚拟域加载**外部私有目录**资源，规避 `file://` 的 fetch/XHR 跨域限制；开启 JS、DOM Storage、媒体自动播放；`InternalStoragePathHandler` 仅支持 `filesDir` 下目录，故使用自定义 `PathHandler`（含路径穿越校验 + MIME 推断）。
- **子资源 404 不降级**：`onReceivedError` 仅在 `request.isForMainFrame` 时降级 `file://` 兜底加载——子资源个别缺失（如音效）不应触发整页重载。
- **`app.zip` 解压**（`ZipUtils.kt`）：覆盖式解压（对应游戏端 `unzip -o` 语义，不删除框架旧文件）、Zip Slip 防护、临时文件原子替换；支持直接读 APK assets 流（首启解压无需中间落盘）。
- 更新：OkHttp + 协程 + 信号量并发（6），文件先写 `.tmp`、MD5 校验通过后原子改名，避免半截文件；下载 URL 按路径段分别 URL 编码（兼容中文文件名）。
