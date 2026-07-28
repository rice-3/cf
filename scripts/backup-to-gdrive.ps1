<#
.SYNOPSIS
    プロジェクトを G ドライブ（Google ドライブ）へバックアップする。

.DESCRIPTION
    **正はこのリポジトリ（F:\11\CF）である。** G ドライブはバックアップ先であり、
    参照元にしない。G はマウントが安定しないため、繋がっていないときは何もせず終了する。

    生成物（node_modules / build / .next 等）は除外する。復元は Git と npm/gradle で行うため、
    バックアップの目的は「端末が壊れたときに手元の成果物を失わないこと」に絞る。

.PARAMETER Destination
    バックアップ先。既定は G:\マイドライブ\CF-backup。

.PARAMETER DocsOnly
    docs 配下だけをバックアップする。設計書だけ退避したいときに使う。

.PARAMETER WhatIf
    実際にはコピーせず、対象だけを表示する。

.EXAMPLE
    pwsh -File scripts/backup-to-gdrive.ps1

.EXAMPLE
    pwsh -File scripts/backup-to-gdrive.ps1 -DocsOnly
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Destination = "G:\マイドライブ\CF-backup",
    [switch]$DocsOnly
)

$ErrorActionPreference = "Stop"

# リポジトリルート（本スクリプトの1つ上）
$repoRoot = Split-Path -Parent $PSScriptRoot
Write-Host "リポジトリ: $repoRoot"
Write-Host "バックアップ先: $Destination"

# G ドライブが繋がっていないことは異常ではない。何もせず正常終了する。
$destRoot = Split-Path -Qualifier $Destination
if (-not (Test-Path $destRoot)) {
    Write-Warning "$destRoot が参照できません。バックアップをスキップします（G は不安定なため異常ではない）。"
    exit 0
}

if (-not (Test-Path $Destination)) {
    if ($PSCmdlet.ShouldProcess($Destination, "作成")) {
        New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    }
}

# 生成物と秘密情報は持ち出さない。復元は Git / npm ci / gradlew で行う。
$excludeDirs = @(
    ".git", ".gradle", ".kotlin", ".idea", ".vscode",
    "node_modules", "build", ".next", "out",
    "coverage", "test-results", "playwright-report",
    ".terraform"
)
$excludeFiles = @(
    "*.env", ".env.*", "*.pem", "*.p12", "*.log", "*.tfstate", "*.tfstate.*"
)

$source = if ($DocsOnly) { Join-Path $repoRoot "docs" } else { $repoRoot }
$target = if ($DocsOnly) { Join-Path $Destination "docs" } else { $Destination }

# /MIR は宛先の余分なファイルを消す。バックアップとして鏡像にしたいので採用する。
# /R:2 /W:2 … G が不安定なので待たずに諦める。/NFL /NDL … ログを短く。
$roboArgs = @(
    $source, $target, "/MIR", "/R:2", "/W:2", "/NFL", "/NDL", "/NP", "/NJH"
)
foreach ($d in $excludeDirs) { $roboArgs += @("/XD", $d) }
foreach ($f in $excludeFiles) { $roboArgs += @("/XF", $f) }
if ($WhatIfPreference) { $roboArgs += "/L" }

Write-Host "robocopy 実行中..."
& robocopy @roboArgs | Out-String -Stream | Select-Object -Last 12

# robocopy の終了コードは 0-7 が成功、8 以上が失敗（ビット合成）。
$code = $LASTEXITCODE
if ($code -ge 8) {
    Write-Error "robocopy が失敗しました（exit=$code）。"
    exit $code
}

Write-Host "バックアップ完了（robocopy exit=$code。0-7 は正常）。"
exit 0
