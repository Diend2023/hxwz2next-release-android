# sync_baseline.ps1 — 生成 APK 内置热更新基线
#
# 职责：
#   1. 从 other/ 复制豁免文件（index.html、favicon.png）→ app/src/main/assets/www/
#   2. 从 repo/hxwz2-release 复制随包 md5s.json（强制无 BOM）
#   3. 复制随包 app.zip（首次启动时由壳解压生成游戏框架）
#   4. 按游戏端 keyToLocalPath 映射复制资源：
#        repo/abn/     → assets/www/assets/roles/
#        repo/icons/   → assets/www/assets/icons/
#        repo/maps/    → assets/www/assets/map/   （注意单数）
#        repo/sounds/  → assets/www/assets/sounds/
#
# ⚠ 前置条件：repo/hxwz2-release 需先 git pull 至目标版本，
#    app.zip / md5s.json / 资源目录必须同源于同一提交（md5s.json 由仓库 git hooks 自动生成）。
#
# 用法：在项目根目录执行  powershell -ExecutionPolicy Bypass -File tools\sync_baseline.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$repo = Join-Path $root "repo\hxwz2-release"
$www = Join-Path $root "app\src\main\assets\www"
$other = Join-Path $root "other"

if (-not (Test-Path $repo)) {
    Write-Error "未找到热更仓库 clone：$repo （请先 git clone https://cnb.cool/anime-battle-next/hxwz2-release 到 repo/hxwz2-release）"
}
if (-not (Test-Path (Join-Path $repo "app.zip"))) {
    Write-Error "热更仓库缺少 app.zip：$repo"
}

# UTF8 无 BOM 编码（PowerShell 5.1 的 Encoding.UTF8 会写 BOM，导致客户端 JSONObject 解析失败）
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

Write-Host "==> 清空旧基线：$www"
if (Test-Path $www) { Remove-Item $www -Recurse -Force }
New-Item -ItemType Directory -Force -Path $www | Out-Null

Write-Host "==> 复制豁免文件（other/，含子目录结构）"
Get-ChildItem $other -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($other.Length + 1)
    $target = Join-Path $www $rel
    $targetDir = Split-Path $target -Parent
    if (-not (Test-Path $targetDir)) { New-Item -ItemType Directory -Force -Path $targetDir | Out-Null }
    Copy-Item $_.FullName $target -Force
    Write-Host ("    {0}" -f ($rel -replace '\\', '/'))
}

Write-Host "==> 复制 md5s.json（强制无 BOM）"
$md5sSrc = Join-Path $repo "md5s.json"
if (-not (Test-Path $md5sSrc)) { Write-Error "热更仓库缺少 md5s.json" }
$content = Get-Content $md5sSrc -Raw
if ($content[0] -eq [char]0xFEFF) { $content = $content.Substring(1) }
[System.IO.File]::WriteAllText((Join-Path $www "md5s.json"), $content, $utf8NoBom)

Write-Host "==> 复制 app.zip（随包框架包，首启解压）"
Copy-Item (Join-Path $repo "app.zip") $www -Force

# 按映射复制资源目录
$map = @(
    @{ Src = "abn";     Dst = "assets\roles"  },
    @{ Src = "icons";   Dst = "assets\icons"  },
    @{ Src = "maps";    Dst = "assets\map"    },  # 单数映射（与游戏端一致）
    @{ Src = "sounds";  Dst = "assets\sounds" }
)
foreach ($m in $map) {
    $srcDir = Join-Path $repo $m.Src
    if (-not (Test-Path $srcDir)) {
        Write-Warning "跳过（不存在）：$srcDir"
        continue
    }
    $dstDir = Join-Path $www $m.Dst
    Write-Host ("==> 复制 {0}/ → {1}/" -f $m.Src, ($m.Dst -replace '\\', '/'))
    New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
    Get-ChildItem $srcDir -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($srcDir.Length + 1)
        $target = Join-Path $dstDir $rel
        $targetDir = Split-Path $target -Parent
        if (-not (Test-Path $targetDir)) { New-Item -ItemType Directory -Force -Path $targetDir | Out-Null }
        Copy-Item $_.FullName $target -Force
    }
}

# 汇总
$count = (Get-ChildItem $www -Recurse -File).Count
$size = [math]::Round(((Get-ChildItem $www -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host ""
Write-Host "基线生成完成：$count 个文件，共 $size MB"
Write-Host "注意：豁免白名单（index.html、favicon.png）必须与 UpdateManager.LOCKED_FILES 一致。"
