# ADR-0009: 監査アーカイブは専用S3バケットへ Glacier Instant Retrieval で出力し1年保持する

## 状態

採用（2026-07-27、要判断A の決定）

## 背景

BAT-009 監査アーカイブは、保持期限を超えた `audit_log` / `ai_activity_log` を出力してから
DBの行を削除する（詳細設計 §9「S3 Glacier相当へ出力後削除、件数・hash検証」）。
出力先が未確定だったため `LocalAuditArchiveAdapter` がハッシュ算出のみを行い、
**S3へは何も出さないまま行を削除する**状態で `TODO(question)` が残っていた。

### 決めるべきだったこと

| 項目 | 決定 |
|---|---|
| バケット | **専用バケット** `<prefix>-audit-archive` |
| ストレージクラス | **GLACIER_IR**（Glacier Instant Retrieval） |
| 保持年数 | **1年**（S3側ライフサイクルで365日） |

## 保持1年の意味（DB保持期間への上乗せ）

BAT-009 がアーカイブするのは**すでにDBの保持期限を超えた行**である。したがってS3側の
1年は総保持期間ではなく上乗せ分になり、基本設計 §7.7 の要件を上回る。

| データ | DB | ＋S3 | 実質 | §7.7 要件 |
|---|---|---|---|---|
| 監査ログ | 3年（`audit-retention-days: 1095`） | 1年 | **4年** | 3年 ✅ |
| AI利用記録 | 1年（`ai-activity-retention-days: 365`） | 1年 | **2年** | 1年 ✅ |

## 判断とその理由

### 1. ファイル用バケットと分離する

`s3.tf` のファイル用バケットは**ブラウザからの presigned PUT を受けるため CORS を開けており**、
キーは presign 経路から到達しうる。監査アーカイブは「書き込み専用・参照権限限定」で運用したいので、
同じバケットに置かない。ライフサイクル（`pending/` の1日失効）も干渉する。

### 2. ストレージクラスは GLACIER_IR

| 候補 | 最低保存期間 | 取り出し | 判断 |
|---|---|---|---|
| STANDARD_IA | 30日 | 即時 | 保持1年に対して割高 |
| **GLACIER_IR** | 90日 | ミリ秒 | **採用**。1年保持に収まり、監査調査で即座に読める |
| DEEP_ARCHIVE | 180日 | 12時間程度 | 最安だが、監査調査で12時間待つのは実用性を損なう |

教育用のデータ量では絶対額の差が小さいため、**取り出しやすさを優先**した。

**ライフサイクルの transition ではなく、PUT時に直接ストレージクラスを指定する。**
S3のライフサイクル遷移は既定で128KB未満のオブジェクトをGlacier系へ遷移させないため、
小さなアーカイブが STANDARD に残り続ける事故を避ける。

### 3. アプリには書き込み権限だけを与える

タスクロールには `s3:PutObject` のみ付与し、`GetObject` も `DeleteObject` も与えない
（基本設計 §7.7「改ざん防止、参照権限限定」）。アプリ経由での改ざん・削除ができない。

この結果 **書き込んだ内容を読み直して検証することはできない**。代わりに PUT へ SHA-256
チェックサムを添えて **S3側で検証**させる。不一致ならS3が PUT を拒否するため、
「返ってきたハッシュ = S3が受理した内容のハッシュ」が保証される。
参照は運用者が別権限（Identity Center のロール）で行う。

### 4. Object Lock は「有効化はするが既定の保持ルールは置かない」

`object_lock_enabled = true` を**バケット作成時に**設定する。Object Lock は後付けできないため、
capability だけ先に開けておき、既定の保持ルールは `audit_archive_lock_days`（既定 `0` = 無効）で
制御する。これなら production で改ざん防止を強制する段階になっても、
**バケットを作り直さずに**変数を指定するだけで済む。

**既定で有効にしない理由**: COMPLIANCE モードはルートユーザーでも解除できないため、
指定日数が経つまでバケットを空にできず **`terraform destroy` が失敗する**。
要判断F で「都度 apply/destroy」運用（月数千円）が候補に挙がっている dev では致命的である。
モードの既定は `GOVERNANCE`（`s3:BypassGovernanceRetention` を持つ主体が解除できる）とした。

## 影響

追加・変更したもの:

| 対象 | 内容 |
|---|---|
| `infra/terraform/s3_audit_archive.tf` | 専用バケット（公開ブロック / バージョニング / SSE-S3 / Object Lock capability / ライフサイクル365日） |
| `infra/terraform/variables.tf` | `audit_archive_retention_days` / `audit_archive_lock_days` / `audit_archive_lock_mode` |
| `infra/terraform/iam.tf` | タスクロールへ `s3:PutObject` のみ（`AuditArchivePutOnly`） |
| `infra/terraform/ecs.tf` | `CF_AUDIT_ARCHIVE_BUCKET` 注入 |
| `S3AuditArchiveAdapter` | 新規。`@Profile("!local & !test")` |
| `LocalAuditArchiveAdapter` | `@Profile({"local","test"})` を追加（**従来は無条件Beanで、S3実装と重複する**） |
| `AuditArchiveProperties` | `cf.audit.archive.{bucket,key-prefix,storage-class}` |

### 出力先が未設定なら必ず失敗させる

`bucket` が空のまま dev 以上で動くと、S3へ出さずにハッシュを返し **BAT-009 がDBを削除する**。
`S3AuditArchiveAdapter` は bucket 未設定を `DependencyException` にして落とす。
BAT-009 はハッシュを得られない限り削除しないため、データは次回へ持ち越される。

### オブジェクトキー

`<keyPrefix>/<archiveName>/<実行時刻>.json`。`archiveName` は
`audit_log_until_2023-07-27T00:00:00Z` の形で `:` を含むため、キーでは `-` へ置換する。
実行時刻を含めることで、同一 `archiveName` の再実行でも既存を上書きしない。

## 残る前提

- **実S3への出力確認は dev 環境構築後**（AWS必須）。ローカルでは `LocalAuditArchiveAdapter` が動くため、
  S3経路そのものは未検証である。
- 監査アーカイブの**参照手順**（運用者がどの権限でどう取り出すか）は本ADRの範囲外。
  `docs/ops/runbook.md` へ追記する余地がある。
- production で Object Lock を有効化する場合、`COMPLIANCE` を選ぶと destroy 不能になる点を
  運用手順に織り込むこと（`docs/ops/aws-contract-build-runbook.md` §19 破棄手順）。
