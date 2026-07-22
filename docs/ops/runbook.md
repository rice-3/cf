# 運用手順書（Runbook） — CF-Training

- 対象: バックエンド `cf-api` / フロントエンド（BFF）
- 上位: 基本設計 BD-CF-001 §8（バッチ）/§11（運用・監査）、詳細設計 DD-CF-001 §9（バッチ）/§12–13（運用）
- 関連: メトリクス・アラートは `docs/ops/monitoring.md`、IaC は `infra/terraform/README.md`
- 対象ロール: **OPERATOR**（返金・照合の運用操作）/ **ADMIN**（会員・監査）/ **AUDITOR**（監査ログ参照）

> **重要（要件 C-17）**: 本番での手動データ変更（SQL直接更新等）は原則禁止。運用操作は
> 運用API（SCR-060/061）を通じて行い、すべて監査ログ（`audit_log`）へ記録する。
> 調査目的のSQLは**参照（SELECT）のみ**に留める。やむを得ず変更する場合は承認と記録を残す。

---

## 1. 日常監視

| 対象 | 手段 |
|---|---|
| 稼働 | `GET /actuator/health`（liveness/readiness、ALB/ECSヘルスチェック） |
| メトリクス | `GET /actuator/prometheus` → CloudWatch/Grafana（閾値は `docs/ops/monitoring.md`） |
| 監査ログ | SCR-071 監査ログ検索（`GET /api/v1/audit-logs`、ADMIN/AUDITOR） |
| AI利用記録 | SCR-071（`GET /api/v1/ai-activities`、ADMIN/AUDITOR） |

主要アラートと一次対応の対応表は §7 を参照。

---

## 2. バッチ運用

### 2.1 バッチ一覧（周期・多重起動防止）

| ID | 処理 | 起動 | 既定周期 | ロック |
|---|---|---|---|---|
| BAT-001 | 公開開始 | scheduled | 60s | ShedLock `BAT-001-publish` |
| BAT-002 | 募集終了 | scheduled | 60s | ShedLock `BAT-002-close-funding` |
| BAT-003 | 返金対象作成 | **イベント駆動**（ProjectFailed） | — | Outbox配送（BAT-006）経由 |
| BAT-004 | 返金実行 | scheduled | 60s | ShedLock `BAT-004-refund` |
| BAT-005 | 通知送信 | scheduled | 60s | ShedLock `BAT-005-notification` |
| BAT-006 | Outbox配送 | scheduled | 5s | 競合コンシューマ（`FOR UPDATE SKIP LOCKED`） |
| BAT-007 | 決済照合 | scheduled | 900s(15分) | ShedLock `BAT-007-reconcile` |
| BAT-008 | ファイル清掃 | cron | `0 30 3 * * *` | ShedLock `BAT-008-file-cleanup` |
| BAT-009 | 監査アーカイブ | cron | `0 0 4 1 * *` | ShedLock |
| BAT-010 | 冪等記録削除 | cron | `0 15 3 * * *` | ShedLock `BAT-010-idempotency-cleanup` |

- 多重起動は ShedLock（`shedlock` テーブル、DB時刻基準）で防止（ADR-0003）。BAT-006 は競合コンシューマ設計のため対象外。
- 停止・遅延の検知は `cf_batch_last_success_age_seconds{batch}` を監視（§7）。

### 2.2 バッチ再実行

本システムのバッチは **@Scheduled による自動周期実行**で、任意起動用の管理APIは持たない。
再実行は以下の方針で行う。

1. **次回周期を待つ（第一選択）**
   周期が短い（5〜60秒）バッチは、一時的失敗でも次回実行で自然回復する。まずメトリクスとログで
   回復傾向（`cf_batch_last_success_age_seconds` の低下、`cf_outbox_pending_count` の減少）を確認する。

2. **対象単位の運用操作で個別に処理（返金・照合）**
   特定の返金・決済が滞留している場合は、バッチ全体ではなく対象を運用APIで処理する（§3、§4）。

3. **インスタンス再起動（scheduled再起動）**
   スケジューラ自体が停止している疑い（全バッチの `last_success_age` が増加し続ける）がある場合は、
   ECSタスクをローリング再起動する。ShedLock により多重起動は起きない。
   異常終了時のロックは `lockAtMostFor` 経過後に解放される。

4. **cronバッチ（BAT-008/009/010）の当日再実行**
   日次/月次バッチを当日中に再実行したい場合もタスク再起動では起動しない（cron時刻待ち）。
   緊急時は一時的に cron 式を近い将来へ変更してデプロイ→実行後に戻す。恒常運用では翌周期を待つ。

> バッチ処理はいずれも冪等・対象単位ロック（`FOR UPDATE SKIP LOCKED`）で設計されており、
> 再実行による二重処理は起きない（詳細設計 §9）。

---

## 3. 決済照合（API-PY-002 / BAT-007）

結果不明（`UNKNOWN`）のまま滞留した決済をProviderへ照会し、状態を確定させる。

- **自動**: BAT-007 が15分ごとに実行。
- **手動**: SCR-060 運用コンソール → 対象支援の「決済照合」ボタン、または
  `POST /api/v1/operations/payments/{paymentId}/reconcile`（OPERATOR/ADMIN）。

手順:
1. SCR-060 で対象支援を検索（決済状態が `UNKNOWN` のもの）。
2. 「決済照合」を実行。応答に確定後の決済状態が返る。
3. まだ `UNKNOWN` の場合はProvider側が未確定。時間をおいて再照会（次回BAT-007でも自動再試行）。

---

## 4. 返金の手動対応（API-RF-001/002）

### 4.1 返金要求（新規）

募集不成立の一括返金は BAT-003（イベント駆動）が自動生成する。運用判断による個別返金は手動で行う。

- SCR-061/060 運用コンソール → 対象支援の「返金要求」、または
  `POST /api/v1/operations/supports/{supportId}/refunds`（**Idempotency-Key 必須**）。
- `reasonCode`: `PROJECT_FAILED` / `OPERATIONAL`（**comment必須**） / `USER_CANCEL`。
- `amount` 省略時は全額返金。返金は次回 BAT-004 で実行される。

### 4.2 返金再実行（失敗した返金）

再試行上限を超え `FAILED` で滞留した返金を運用者が再実行する。

- SCR-061 で状態 `FAILED` を絞り込み → 「再実行」、または
  `POST /api/v1/operations/refunds/{refundId}/retry`。
- 再実行すると `REQUESTED` に戻り、次回 BAT-004 で処理される。
- 成功済み（`SUCCEEDED`）の返金は再実行不可（409 `REFUND_INVALID_STATE`）。

### 4.3 返金状態の意味

`REQUESTED`（要求済・実行待ち）/ `PROCESSING`（処理中）/ `RETRY_WAIT`（再試行待ち、指数Backoff）/
`SUCCEEDED`（完了）/ `FAILED`（上限超過・要手動対応）。

---

## 5. Outbox滞留・通知失敗の対応

### 5.1 Outbox滞留（`cf_outbox_pending_count` 増加）

1. `cf_outbox_oldest_age_seconds` で滞留時間を確認。
2. アプリログで配送失敗原因（配送先の障害等）を確認。BAT-006 は 5s 周期で自動再試行（Backoff 1m→5m→15m→1h→6h）。
3. 上限（既定5回）超過で `publish_status = ERROR` 固定になる。配送先復旧後、当該イベントの再送可否を判断する。
4. **影響波及**: BAT-003 返金対象作成は ProjectFailed の Outbox 配送に依存する。Outbox 停止時は
   募集不成立の返金生成も止まるため、Outbox復旧を最優先で対応する。

### 5.2 通知失敗（`cf_notification_failed_count` / 失敗率）

1. `cf_notification_delivery_total{result}` で失敗率を確認。
2. BAT-005 が `PENDING`/`RETRY_WAIT` を自動再送。上限超過で `FAILED` 確定。
3. SES側障害が疑われる場合は送信元・SES状態を確認。復旧後の再送は個別判断。

---

## 6. 障害時の切り分け

### 6.1 相関ID（Correlation ID）による追跡

- リクエストヘッダー `X-Correlation-Id`（未指定時はサーバー採番）。応答にも同ヘッダーを返す。
- Problem Details（RFC 9457）応答の `correlationId`、および全ログ行の `[correlationId]`（MDC）で追跡する。
  ログパターン: `%5p [%X{correlationId}]`。
- バッチ実行は起動ごとに `bat_<uuid>` 形式の相関IDを採番する。

### 6.2 切り分けフロー

1. 事象の相関IDを取得（ユーザー報告の画面エラー、5xx応答、アラート）。
2. アプリログを相関IDで grep し、該当リクエスト/バッチの一連の処理を追う。
3. 監査ログ（SCR-071）を相関ID・実行者・対象で検索し、操作系（返金・審査・ロール変更等）の実行結果を確認する。
4. メトリクス（§7）で影響範囲（滞留件数・失敗率・レイテンシ）を把握する。
5. 決済・返金の状態は運用コンソール（SCR-060/061）で対象を特定し、§3–4 に従って対処する。

### 6.3 調査用の参照クエリ（SELECTのみ）

```sql
-- 滞留Outbox（未配送）
select publish_status, count(*), min(occurred_at)
  from outbox_event where publish_status in ('PENDING','ERROR') group by publish_status;

-- 失敗返金
select refund_id, support_id, status, retry_count, updated_at
  from refund where status in ('FAILED','RETRY_WAIT') order by updated_at;

-- 結果不明の決済
select payment_id, status, updated_at from payment where status = 'UNKNOWN' order by updated_at;

-- バッチのロック保持状況（ShedLock）
select name, lock_until, locked_at, locked_by from shedlock order by lock_until desc;
```

---

## 7. アラート → 一次対応 対応表

| アラート（メトリクス） | 一次対応 |
|---|---|
| `cf_batch_last_success_age_seconds` 高騰（全バッチ） | スケジューラ停止の疑い → ECSタスク再起動（§2.2-3） |
| `cf_batch_last_success_age_seconds{batch=...}` 個別高騰 | 当該バッチのログ確認。対象滞留なら §3/§4 で個別処理 |
| `cf_outbox_pending_count` / `oldest_age` 増加 | §5.1。配送先障害を確認、Outbox復旧を最優先 |
| `cf_notification_failed_count` / 失敗率 | §5.2。SES状態確認 |
| `cf_refund_failed_count` > 0 | §4.2。SCR-061 で再実行 or 原因調査 |
| `cf_refund_retry_wait_count` 増加 | 決済プロバイダ障害の疑い。回復を監視、必要に応じ §4.2 |
| API 5xx率 / p95 レイテンシ SLO超過 | §6。相関IDで原因特定、必要ならスケール/ロールバック（`cd.yml`） |

---

## 8. デプロイ・ロールバック

- デプロイは `cd.yml`（手動 `workflow_dispatch`、環境承認付き、OIDC）。ECRへpush → ECSローリング更新。
- ロールバックは直前のイメージタグで `cd.yml` を再実行する。
- 前提のAWSリソース整備は残タスク（`docs/ses_ai_ddd_remaining_tasks.md` §2.1）。詳細は `infra/terraform/README.md`。

---

## 9. エスカレーション時に添える情報

- 相関ID、発生時刻（UTC）、対象ID（support/refund/payment/project）、影響件数（メトリクス値）、
  実施済みの一次対応と結果。監査ログの該当エントリ（`audit_id`）。
