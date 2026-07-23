# 監視・アラート設計（CF-Training）

- 対象: バックエンド `cf-api`（Spring Boot 4 / Micrometer）
- 上位: 基本設計 §12.5–12.6 / 詳細設計 §9.3
- 収集方式: Micrometer → Prometheus 形式で公開 → OTel Collector / CloudWatch Agent が収集
- スクレイプ先: `GET /actuator/prometheus`

## 1. エンドポイント

| エンドポイント | 用途 | 認可 |
|---|---|---|
| `/actuator/health` / `/actuator/health/{liveness,readiness}` | ヘルスチェック（ECS/ALB） | 公開 |
| `/actuator/prometheus` | メトリクススクレイプ | 公開（下記の注意） |
| `/actuator/metrics` | 個別メトリクス参照（デバッグ） | 要認証 |
| `/actuator/info` | ビルド情報 | 公開 |

> **本番でのセキュリティ**: `/actuator/prometheus` は認証なしで公開している。メトリクス本文に機密は
> 含めていないが、ALB のリスナールールで `/actuator/**` を**外部公開しない**こと（VPC内の
> Collector / CloudWatch Agent のみが到達する構成）。完全に無効化する場合は
> `management.endpoints.web.exposure.include` から `prometheus` を除外する。

## 2. メトリクスカタログ

### 2.1 ビジネス滞留・失敗（`BusinessMetrics`、スクレイプ時にDB集計）

| メトリクス | 型 | 意味 |
|---|---|---|
| `cf_outbox_pending_count` | gauge | 未配送Outboxイベント数（`PENDING`/`ERROR`） |
| `cf_outbox_oldest_age_seconds` | gauge | 最古の未配送イベントの経過秒 |
| `cf_notification_pending_count` | gauge | 送信待ち通知数（`PENDING`/`RETRY_WAIT`） |
| `cf_notification_failed_count` | gauge | 送信失敗で確定した通知数（`FAILED`） |
| `cf_refund_retry_wait_count` | gauge | 再試行待ちの返金数（`RETRY_WAIT`） |
| `cf_refund_failed_count` | gauge | 再試行上限超過で滞留する返金数（`FAILED`） |

### 2.2 通知送信レート（`NotificationPersistenceAdapter`）

| メトリクス | 型 | 意味 |
|---|---|---|
| `cf_notification_delivery_total{result}` | counter | 送信試行の結果別回数。失敗率 = `rate(result=FAILURE)/rate(total)` |

### 2.3 バッチ稼働（`BatchMetrics`）

| メトリクス | 型 | 意味 |
|---|---|---|
| `cf_batch_last_success_age_seconds{batch}` | gauge | 最終成功からの経過秒（未実行は NaN） |
| `cf_batch_runs_total{batch,outcome}` | counter | バッチ実行回数（`outcome`=success/failure） |

`batch` タグ: `BAT-001-publish` / `BAT-002-close-funding` / `BAT-004-refund` / `BAT-005-notification` /
`BAT-006-outbox` / `BAT-007-reconcile` / `BAT-008-file-cleanup` / `BAT-010-idempotency-cleanup`。

### 2.4 API（actuator標準 `http.server.requests`）

`application.yml` でヒストグラムと SLO バケット（100ms/300ms/500ms/1s）を有効化済み。

| 指標 | 算出（PromQL例） |
|---|---|
| 5xx率 | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` |
| p95 レイテンシ | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))` |

すべてのメーターに共通タグ `application="cf-api"` を付与する（`ObservabilityConfig`）。

## 3. アラート閾値（初期値）

環境・負荷特性に応じて調整する。周期の速いバッチ（1分）は経過秒の閾値を短く、日次/月次は長く取る。

| メトリクス | Warning | Critical | 備考 |
|---|---|---|---|
| `cf_outbox_pending_count` | > 100（5分継続） | > 1000（5分継続） | 配送停止・連続失敗 |
| `cf_outbox_oldest_age_seconds` | > 300 | > 1800 | 滞留時間 |
| `cf_notification_pending_count` | > 200（10分継続） | > 2000 | 送信遅延 |
| `cf_notification_failed_count` | > 0 | > 50 | FAILED は手動対応対象 |
| 通知失敗率 | > 5%（15分） | > 20%（15分） | `cf_notification_delivery_total` から算出 |
| `cf_refund_retry_wait_count` | > 20 | > 100 | 決済プロバイダ障害の疑い |
| `cf_refund_failed_count` | > 0 | > 10 | 運用者の手動返金・照合が必要 |
| `cf_batch_last_success_age_seconds{batch}`（1分周期） | > 300 | > 900 | BAT-001/002/004/005/006 |
| `cf_batch_last_success_age_seconds{batch=BAT-007-reconcile}` | > 2700 | > 5400 | 15分周期 |
| `cf_batch_last_success_age_seconds{batch}`（日次） | > 129600 (36h) | > 172800 (48h) | BAT-008/010 |
| API 5xx率 | > 1%（5分） | > 5%（5分） | |
| API p95 レイテンシ | > 500ms（5分） | > 1s（5分） | SLO |

## 4. 実装状況・残作業

- [x] メトリクス公開（Micrometer + `/actuator/prometheus`）、ビジネス/バッチ/API メトリクス。
- [x] 本書のアラート閾値定義。
- [x] CloudWatch Alarm / ダッシュボード / SNS通知の Terraform 化（`infra/terraform/monitoring.tf`、`validate` 済）。
      本書の閾値を実装（インフラ系はALB/ECS/RDS、ビジネス系は `var.metrics_namespace` のカスタム指標）。
- [ ] メトリクスパイプライン（CloudWatch Agent(Prometheus)/ADOT Collector）で `/actuator/prometheus` を
      `var.metrics_namespace` へ発行する構成（ECSサイドカー等）。ビジネス/バッチアラームはこの発行後に有効化。
- [ ] 実 `apply` とSNSメール購読の確認（AWS必須）。ダッシュボードの微調整。
