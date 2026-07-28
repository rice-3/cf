# docs/des — 上位文書（要件・設計）

本フォルダは**上位文書**（要件定義・要件確認・技術選定・基本設計・詳細設計）を置く。
実装状況や残タスクといった**進行管理の文書は `docs/` 直下**にあり、ここには置かない。

## 収録物

| ファイル | 文書ID | 版数 | 備考 |
|---|---|---|---|
| `ses_ai_ddd_requirements_tech_selection.md` | — | 1.1 | 要件定義書・要件確認書・技術選定書（統合） |
| `ses_ai_ddd_basic_design.md` | BD-CF-001 | 1.3 | 基本設計書 |
| `ses_ai_ddd_detailed_design.md` | DD-CF-001 | 1.3 | 詳細設計書 |
| `ses_ai_ddd_basic_design.docx` / `ses_ai_ddd_detailed_design.docx` | 同上 | 1.3 | `.md` から pandoc で再出力済み（2026-07-28） |
| `01_`〜`05_*.docx` | — | — | 初期の要件・概要・技術一覧・開発計画 |

## `.md` と `.docx` の関係

**`.md` を正とする。** `.docx` は `.md` から pandoc で再出力する。
**2026-07-28 に 1.3 で同期済み**（pandoc 3.10）。

再出力は、**直前の `.docx` 自身を `--reference-doc` に渡して書式を引き継ぐ**。
上書き対象をそのまま参照はできないので、先にコピーを取る。

```bash
cd docs/des
for n in basic detailed; do
  cp "ses_ai_ddd_${n}_design.docx" "/tmp/ses_ai_ddd_${n}_design.ref.docx"
  pandoc "ses_ai_ddd_${n}_design.md" -o "ses_ai_ddd_${n}_design.docx" --reference-doc="/tmp/ses_ai_ddd_${n}_design.ref.docx"
done
```

出力後は中身で確認する（バイナリなので diff では分からない）。

```bash
pandoc ses_ai_ddd_basic_design.docx -t plain | grep -E "版数|1\.3"
```

## 版数 1.3（2026-07-28）について

**実装を正としてコードと突き合わせ、乖離していた記述を更新した。**
上位文書を実装へ合わせる変更なので、各書の「変更履歴」に理由と根拠ADRを記録してある。
主な内容は次のとおり。

- SQS の不採用（ADR-0008）を A-07・構成図・テスト戦略・技術選定へ反映
- バッチ表を実装の採番・周期・ShedLock適用へ一致させ、BAT-003 / BAT-010 を追加
- Gradleモジュール構成を単一Backendプロジェクトの実態へ更新（ADR-0001）
- 会員の初回登録（JITプロビジョニング、ADR-0007）と監査アーカイブのS3保持（ADR-0009）を追記

差分の背景は `../ses_ai_ddd_problem_catalog.md`（問題と解決の記録）を参照。

## `_archive_2026-07-20/`

`docs/` 直下にある進行管理文書の **2026-07-20 時点のコピー**。当初このフォルダへ
同名で置かれていたが、`docs/` 側が更新され続けて内容が乖離したため退避した。
**参照する場合は必ず `docs/` 直下の現行版を使うこと。**

| ファイル | 現行版の場所 |
|---|---|
| `ses_ai_ddd_implementation_status.md` | `docs/ses_ai_ddd_implementation_status.md` |
| `ses_ai_ddd_remaining_tasks.md` | `docs/ses_ai_ddd_remaining_tasks.md` |
| `session_report_2026-07-20.md` | `docs/session_report_2026-07-20.md`（内容は同一） |
