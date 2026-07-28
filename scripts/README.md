# scripts

## backup-to-gdrive.ps1

プロジェクトを G ドライブ（Google ドライブ）へバックアップする。

### 前提: 正はこのリポジトリ

**設計書を含むすべての文書は `F:\11\CF`（このリポジトリ）を正とする。**
以前は上位文書を `G:\マイドライブ\CF\` に置いていたが、**G はマウントが安定せず
参照できないことがある**ため、リポジトリ側へ移した（`docs/des/`）。

G ドライブは**バックアップ先**であり、参照元にしない。
G 上のファイルを編集しても、次回のバックアップで上書き（`/MIR`）されて消える。

### 使い方

```powershell
# 全体（既定の宛先: G:\マイドライブ\CF-backup）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup-to-gdrive.ps1

# docs 配下だけ
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup-to-gdrive.ps1 -DocsOnly

# 宛先を変える／実行せず対象だけ見る
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup-to-gdrive.ps1 -Destination "D:\backup\CF" -WhatIf
```

### 挙動

| 状況 | 挙動 |
|---|---|
| G が参照できない | **警告を出して exit 0**。Gが不安定なのは異常ではないため失敗扱いにしない |
| G が参照できる | `robocopy /MIR` で鏡像コピー。宛先の余分なファイルは削除される |
| robocopy exit 0〜7 | 正常（1=コピーあり、2=余分削除あり 等のビット合成） |
| robocopy exit 8以上 | エラーとして非ゼロで終了 |

### 除外するもの

生成物と秘密情報は持ち出さない。復元は Git / `npm ci` / `gradlew` で行うため、
バックアップの目的は「端末が壊れたときに手元の成果物を失わないこと」に絞っている。

- ディレクトリ: `.git` `.gradle` `.kotlin` `.idea` `.vscode` `node_modules` `build`
  `.next` `out` `coverage` `test-results` `playwright-report` `.terraform`
- ファイル: `*.env` `.env.*` `*.pem` `*.p12` `*.log` `*.tfstate*`

実測で **299ファイル / 約2.0MB**（2026-07-28 時点）。

### 注意

- **`.ps1` は UTF-8 BOM 付きで保存すること。** Windows PowerShell 5.1 は BOM 無し UTF-8 を
  ANSI として読むため、日本語コメントが文字化けして構文エラーになる（実際に踏んだ）
- `.git` を除外しているので、**このバックアップから履歴は復元できない**。
  コードの正本は GitHub（`rice-3/cf`）側にある
