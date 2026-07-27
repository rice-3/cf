# ADR-0008: Outbox配送はアプリ内Handlerで行う（SQSを挟まない）

## 状態

採用（2026-07-27、要判断B の決定）

## 背景

BAT-006 Outbox配送の配送先が「SQS か アプリ内か」未確定のまま、
**アプリ内実装（`InProcessOutboxDispatcher`）と、SQSキュー一式のIaCの両方**が存在していた。

`InProcessOutboxDispatcher` には「dev以上でSQSへ切り替える場合は本クラスをSQS Adapterへ
差し替える（ADR候補）」というコメントが残り、Terraform 側には使われていないキュー・DLQ・
task roleのSQS権限・`CF_OUTBOX_SQS_QUEUE_URL` があった（§2.3-(4) で「渡しているが
読まれていない変数」として検出）。

### 設計書はどちらも許容している

| 文書 | 記載 |
|---|---|
| 基本設計 §8.1 BAT-006 | 未配送イベントを **SQSまたはアプリ内へ**配送する |
| 詳細設計 §9.1 BAT-003 | **SQS/内部Handler** へ配送 |

つまりこれは仕様違反の是正ではなく、**許容された2案のどちらを正式構成とするか**の決定である。

## 選択肢

1. **アプリ内配送を正式構成とし、未使用のSQS資産を削除する**
2. アプリ内配送のままIaCは将来のために残す
3. SQS配送へ切り替える

## 判断

**選択肢1** を採用する。

## 判断理由

### SQSを挟んでも配送先が変わらない

Outboxイベントの購読側は `@EventListener` が3つだけで、いずれも**同一JVM内**にある。

| 購読者 | コンテキスト |
|---|---|
| `NotificationEventHandler` | notification |
| `PaymentRequestedHandler` | payment |
| `ProjectFailedHandler` | payment |

詳細設計 §3 のモジュール構成には `app-worker`（Batch / SQS Worker）があったが、
**ADR-0001 で単一backendプロジェクトへ統合済み**である。したがってSQSを導入しても
「Worker → SQS → 同一アプリのポーラー → 同じ3ハンドラ」となり、
プロセス分離という本来の利得が発生しない。

### コストは実在する

- AWS SDK `sqs` 依存とポーラー実装の追加
- **at-least-once による重複配送**への対処（現状はDB行を `FOR UPDATE SKIP LOCKED` で
  排他し、成功時に `PUBLISHED` へ落とすため重複しない）
- DLQ の運用・監視の追加
- ローカル検証のための LocalStack 導入（現状ローカルにAWS依存は無い）

### マルチインスタンスでも現状で破綻しない

`desired_count > 1` の懸念は §2.3 で確認済み。`OutboxWorker` は競合コンシューマ設計
（`FOR UPDATE SKIP LOCKED`）で、ADR-0003 により意図的に ShedLock の対象外としてある。
複数インスタンスが同時に走っても各イベントは1回だけ配送される。

### 未使用資産を残さない

選択肢2は「applyされるが誰も使わないリソース」を残す。これは §2.3-(4) で問題視した
状態そのもので、「SQS経由で配送されているつもり」を誘発する。IaCなので、必要に
なった時点で復活させるほうが安全である。

## 影響

削除するもの:

| 対象 | 内容 |
|---|---|
| `infra/terraform/sqs.tf` | `aws_sqs_queue.outbox` / `outbox_dlq`（ファイルごと削除） |
| `infra/terraform/iam.tf` | task role の `Sqs` statement |
| `infra/terraform/ecs.tf` | `CF_OUTBOX_SQS_QUEUE_URL` の注入と `TODO(question)` |
| `infra/terraform/outputs.tf` | `outbox_queue_url` |

アプリ側のコード変更は無い（コメントの更新のみ）。`OutboxDispatcher` インターフェースは
残すため、将来の差し替え口は維持される。

> 基本設計 §8.1 は BAT-004（返金実行）・BAT-005（通知送信）のトリガも「SQS」と書いているが、
> 実装は `@Scheduled` + ShedLock のポーリングである。本ADRはこの既存の実装方針を追認する
> ものでもある（同じく §8.1 が許容する範囲内）。

## 将来SQSへ移る条件

次のいずれかが起きたら本ADRを差し替える。

- Worker を別サービス（別ECSサービス / Lambda）へ切り出す
- 配送先にアプリ外の購読者（別システム）が加わる
- 配送処理がAPIリクエストのレイテンシやDB接続を圧迫し、プロセス分離が必要になる
