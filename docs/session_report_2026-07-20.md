# CF-Training 作業セッション報告（2026-07-20）

- 対象リポジトリ: `F:\11\CF`
- 上位文書: 基本設計 BD-CF-001 / 詳細設計 DD-CF-001（`G:\マイドライブ\CF\`）
- 本書は当該セッションで実施した作業の記録。実装済み範囲の網羅的な一覧は
  `ses_ai_ddd_implementation_status.md`、残タスクは `ses_ai_ddd_remaining_tasks.md` を参照。

---

## 1. セッションの範囲

開始時点は第1段階（工程1〜4）完了・第2段階未着手。本セッションで以下を実施した。

| 区分 | 内容 |
|---|---|
| 実装 | 第2段階 工程5（File/S3）・工程6（Funding）・工程7（Payment/Webhook/Outbox配送）・工程8（Notification/Refund/Batch＋運用API） |
| 設計整合 | 基本設計を正として、基本設計 §4.6／詳細設計 §4.3・§8 系の不整合を統一（両設計書 `.md` を v1.2 へ更新） |
| 不具合対応 | `@Transactional` 自己呼出し／`readOnly` × `FOR UPDATE`／テストHTTPクライアントの401本文欠落 |
| ドキュメント | 実装状況MD・残タスクMDの新規作成と更新、README・設計書変更履歴の追記 |

> 工程9（Identity/Admin/Audit）は本セッション終盤に設計確認まで着手したが、実装は次セッション以降。
> フロントエンド・CI/CD・工程10も本セッションの対象外。

---

## 2. 実装した機能

### 2.1 工程5: File / S3（API-FL-001/002）

- `FileObject` 集約（issueUpload / completeUpload / delete）と `Sha256` VO
- 許可MIME・10MB上限・SHA-256照合・5分URL期限（詳細設計 §4.7 / §6.10 / §10.2）
- `FileStoragePort` 抽象化: local/test は `StubFileStorageAdapter`、dev以上は AWS SDK v2 の `S3FileStorageAdapter`
- 第1段階の `StubFileReferenceQuery`（常に true）を `FilePersistenceAdapter` の実検証へ置換
- Migration `V202607200005__create_file_tables.sql`

### 2.2 工程6: Funding（API-FD-001〜004）

- `Support` / `SupportItem` 集約
- `IdempotencyPort` / `IdempotencyPersistenceAdapter`: scope＋actor＋key で一意、request_hash 相違は 409
- リターン数量の条件付き UPDATE（§4.3.1）＋ version 競合時1回再試行、在庫切れは 409 REWARD_SOLD_OUT
- `FundingEligibilityPolicy`（PUBLISHED・期間内・会員 ACTIVE）
- Migration `V202607200006__create_funding_payment_tables.sql`

### 2.3 工程7: Payment / Webhook / Outbox配送

- `Payment` 集約（§4.4 の状態遷移表どおり）
- `SandboxPaymentGatewayAdapter`（HMAC-SHA256 署名検証・5分許容差、実決済事業者へ非接続）
- API-PY-001 Webhook（署名不正 401、正常・重複とも 204、payload_hash 相違は ERROR 記録）
- **Outbox配送 Worker（BAT-006）**: `FOR UPDATE SKIP LOCKED` で 50件/Tx、指数 Backoff、上限5回で ERROR 固定
- 配送 → `InProcessOutboxDispatcher`（ApplicationEvent）→ `PaymentRequestedHandler` が決済開始（§5.3.1）
- Migration `V202607200007__create_payment_webhook_table.sql`

### 2.4 工程8: Notification / Refund / Batch

- `Refund` 集約（request/start/succeed/fail/retry、最大8回リトライ）と `Notification` 集約（最大5回、EMAIL/IN_APP）
- SES 送信 Port: local/test は `MockNotificationSender`、dev以上は `SesNotificationSender`（宛先はハッシュのみログ）
- **バッチ8本**（基本設計 §8.1 の番号）:
  - BAT-001 公開開始 / BAT-002 募集終了 / BAT-003 返金対象作成（`ProjectFailed` 購読）/
    BAT-004 返金実行 / BAT-005 通知送信 / BAT-007 決済照合 / BAT-008 ファイル清掃 / BAT-009 監査アーカイブ
  - すべて `FOR UPDATE SKIP LOCKED` で多重起動に対応
- **運用操作API**: API-RF-001 返金要求 / API-RF-002 返金再実行 / API-PY-002 決済照合（OPERATOR/ADMIN 限定）
- Migration `V202607200008__create_refund_notification_tables.sql`

---

## 3. 設計書の整合（基本設計を正として統一）

基本設計 BD-CF-001 を上位・正とし、詳細設計・実装の差異を統一した。`.md` は両書とも v1.2。

| # | 差異 | 対応 |
|---|---|---|
| 1 | 支援状態名称（§3.5 vs §4.3） | 基本設計の9状態へ統一。`AUTHORIZED` 廃止、`REFUNDING`/`REFUND_FAILED` 追加 |
| 2 | バッチID（§8.1 vs §9） | 基本設計の番号へ統一（Outbox=BAT-006、決済照合=BAT-007） |
| 3 | ハッシュ列型 `char(64)`（5列） | `varchar(64)` へ統一（`ddl-auto: validate` が `bpchar` 不一致を検出するため） |
| 4 | イベント名（§4.6 vs §4.1/§4.3/§4.4） | 基本設計 §4.6 を実装・詳細設計へ寄せた（変更点が最小のため。本原則の例外として変更履歴に記録） |
| 5 | `ProjectFailed` vs `ProjectFundingClosed` | 成立・不成立を別イベント（`ProjectSucceeded`/`ProjectFailed`、共通型 `ProjectFundingResult`）へ分割。BAT-003 は `ProjectFailed` のみ購読すればよく、payload 解釈が不要になった |

> `.docx` は未更新のため再出力が必要。

---

## 4. 発見・修正した不具合

| 事象 | 原因 | 対応 |
|---|---|---|
| **BAT-004 で `No active transaction`** | `@Transactional(REQUIRES_NEW)` を同一クラス内から自己呼出ししており、プロキシを経由せず無効化。工程7の `StartPaymentProcessingService` にも同じ潜在バグがあり、Outbox Worker 経由で偶然動いていた | トランザクション境界を `PaymentTransactionSteps` / `NotificationTransactionSteps` へ別Bean として切り出し |
| `SELECT ... FOR UPDATE` が実行不可 | `readOnly = true` のトランザクションで実行しようとしていた（PostgreSQL が拒否） | 対象取得メソッドの `readOnly` を解除 |
| 401応答の本文が空に見える | **アプリ側ではなくテスト側**。`RestTemplate()` 既定の `HttpURLConnection` が 401 応答の本文を破棄していた（403 では届く） | 結合テストを `RestTemplate(JdkClientHttpRequestFactory())` に統一し、401 の `code` も検証。当初「アプリの不具合」と報告したが誤りだったため訂正済み |

---

## 5. ビルド・テスト検証

各工程完了時に `./gradlew build`（単体 + ArchUnit + Testcontainers 統合）を実行し、いずれも成功を確認。

- 工程8＋運用API 完了時点で全テスト成功（新規統合テストを含む）
- 統合テストで「起案 → 審査 → 公開 → 支援 → 決済 → 募集終了(不成立) → 返金 → 通知」の一気通貫動作を検証
- `ProjectFailed` 重複配送でも返金が二重作成されないこと（部分一意インデックス）を確認

主な追加テスト:

- 単体: `FileObjectTest` / `SupportTest` / `PaymentTest` / `RefundTest` / `NotificationTest` / `SandboxWebhookVerificationTest`
- 統合: `FileUploadFlowIntegrationTest` / `SupportFlowIntegrationTest` / `PaymentWebhookFlowIntegrationTest` /
  `BatchFlowIntegrationTest` / `OperationsApiIntegrationTest`
- ArchUnit: file / funding / payment / notification 各コンテキストの公開契約遵守ルールを追加

---

## 6. 実装時の注意点（既知の落とし穴）

同種の実装を追加する際の再発防止メモ。

- `@Transactional(REQUIRES_NEW)` は同一クラス内の自己呼出しでは無効。外部呼出しを挟む処理は別Beanへ切り出す。
- `SELECT ... FOR UPDATE SKIP LOCKED` は `readOnly` トランザクションで実行できない。
- テストの `RestTemplate()` 既定（`HttpURLConnection`）は 401 応答の本文を破棄する。`JdkClientHttpRequestFactory` を使う。
- Outbox配送・定期バッチは `application-test.yml` で無効化済み。検証は UseCase を直接呼ぶ。
- `ddl-auto: validate` のため、Migration の型と Entity マッピングの不一致は起動失敗になる。

---

## 7. 引き継ぎ・要判断事項

- [ ] 起案者向け通知の宛先解決（イベントへ `ownerUserId` 追加 or Project 公開契約の拡張）。`NotificationEventHandler` に `TODO(question)`
- [ ] 冪等記録の削除バッチの要否（基本設計 §8.1 に項目なし）
- [ ] 監査アーカイブ（BAT-009）の実出力先（現状はハッシュ算出のみのローカル実装）
- [ ] 設計書 `.docx` の再出力（`.md` は両書 v1.2）
- [ ] **git 初回コミットが未実施**（工程5〜8 の成果が未コミット）— 早急な実施を推奨
- [ ] 次工程: 工程9（Identity/Admin/Audit・Cognito 切替）、工程10（監視/CI/CD/E2E/Terraform）、フロントエンド残画面
