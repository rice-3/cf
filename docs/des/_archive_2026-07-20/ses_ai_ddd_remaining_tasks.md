# CF-Training 残タスク一覧

- 対象リポジトリ: `F:\11\CF`
- 上位文書: 基本設計 BD-CF-001 v1.2 / 詳細設計 DD-CF-001 v1.2（`G:\マイドライブ\CF\`）
- 更新日: 2026-07-20（工程8バッチ完了時点）
- 実装済み範囲の詳細は `ses_ai_ddd_implementation_status.md` を参照。

---

## 1. 全体進捗（詳細設計 §16.2 実装順序）

| # | 工程 | 状態 |
|---|---|---|
| 1 | Shared Kernel、Error、Clock、ID、Money | ✅ 完了 |
| 2 | Project集約とDomain Unit Test | ✅ 完了 |
| 3 | Project Repository / Flyway / API / 画面 | ⚠️ API完了・画面はSCR-010のみ |
| 4 | Review集約と審査フロー | ✅ 完了 |
| 5 | File / S3 Adapter | ✅ 完了 |
| 6 | Funding / Payment の内部モデルと冪等性 | ✅ 完了 |
| 7 | Payment Sandbox / Webhook / Outbox配送 | ✅ 完了 |
| 8 | Notification / Refund / Batch | ✅ 完了（起案者向け通知の宛先解決のみ要判断） |
| 9 | Identity / Admin / Audit | ⬜ 未着手 |
| 10 | 監視、CI/CD、E2E、運用手順 | ⬜ 未着手 |

バックエンドの業務フローは「起案 → 審査 → 公開 → 支援 → 決済 → 募集終了 → 返金 → 通知」まで
一気通貫で動作する。残るのは管理系API・フロントエンド・運用基盤。

---

## 2. 工程8の残り（優先度: 高）

バッチ8本、Refund / Notification 集約、運用操作API（API-RF-001 / RF-002 / PY-002）は実装済み。
残りは以下のみ。

- [ ] 起案者向け通知の宛先解決
  - 現在の通知は支援者向け3種のみ（SUPPORT_CONFIRMED / SUPPORT_PAYMENT_FAILED / REFUND_COMPLETED）
  - ProjectApproved / ProjectReturned / ProjectPublished 等はイベントpayloadに起案者UserIdがなく宛先を解決できない
  - **要判断**: イベントへ `ownerUserId` を追加するか、Projectの公開契約に所有者参照を追加するか
  - `NotificationEventHandler` に `TODO(question)` を記載済み
- [ ] SESテンプレートの実登録（テンプレートIDのみ定義済み。本文はSES側で管理）
- [ ] 冪等記録の削除バッチ（詳細設計 §9 BAT-008相当）
  - 基本設計 §8.1 に該当項目がないため**要確認**。`idempotency_record` は24時間で失効するが物理削除されない

---

## 3. 工程9: Identity / Admin / Audit（優先度: 高）

- [ ] API-US-001 プロフィール取得 / API-US-002 プロフィール更新
- [ ] API-AD-001 会員検索 / API-AD-002 ロール更新 / API-AD-003 会員停止
- [ ] API-AU-001 監査ログ検索 / API-AU-002 AI利用記録検索
  - テーブル（`audit_log` / `ai_activity_log`）は作成済み。検索APIのみ未実装
- [ ] **Cognito（OIDC）認証への切替**
  - 現状は local/test プロファイルの開発用ヘッダー認証のみ動作する
  - `ResourceServerSecurityConfig` は雛形のみで、Cognito Subject → 内部UserId の変換が未実装
  - dev環境へ展開する前に必須

---

## 4. 工程10: 監視・CI/CD・E2E・運用（優先度: 中）

- [ ] **`.github/workflows/` のCI構築** — READMEに記載があるがディレクトリ自体が未作成
- [ ] メトリクス・アラート（詳細設計 §12.5–12.6、§9.3 バッチ監視）
  - `outbox_pending_count` / `oldest_outbox_age` / `notification_failure_rate` / `refund_retry_count` / `batch_last_success_age`
- [ ] E2Eテスト
- [ ] Terraform（ADR-007）— `infra/` は現状 docker-compose のみ
- [ ] 運用手順書（バッチ再実行、返金の手動対応、障害時の切り分け）

---

## 5. フロントエンド（優先度: 高、最も未着手）

基本設計 §5.2 の19画面のうち**実装済みは SCR-010 プロジェクト検索の1画面のみ**。

| 区分 | 未実装画面 |
|---|---|
| 公開 | SCR-011 プロジェクト詳細（※SCR-010からリンク済みだが**ページ未実装＝404になる**） |
| OWNER | SCR-020 一覧 / SCR-021 編集 / SCR-022 プレビュー / SCR-023 審査申請確認 |
| REVIEWER | SCR-030 審査一覧 / SCR-031 審査詳細 |
| SUPPORTER | SCR-040 支援入力 / SCR-041 支援確認 / SCR-042 支援結果 / SCR-051 支援履歴 |
| OPERATOR | SCR-060 支援管理 / SCR-061 返金管理 |
| ADMIN | SCR-070 会員・ロール管理 / SCR-071 監査ログ検索 |
| 共通 | SCR-001 ログイン / SCR-002 アクセス拒否 / SCR-050 マイページ / SCR-080 システムエラー |

バックエンドAPIが揃っているため、SCR-011 / SCR-020〜023 / SCR-030〜031 / SCR-040〜042 / SCR-051 は
すぐに着手できる。SCR-060/061（OPERATOR）は §2 の返金APIが前提。

---

## 6. 未確定・要判断事項

| # | 内容 | 影響 |
|---|---|---|
| 1 | 起案者向け通知の宛先解決方法（イベントへownerUserId追加 or Project公開契約の拡張） | 工程8の通知範囲。`NotificationEventHandler` に `TODO(question)` |
| 2 | 冪等記録の削除バッチの要否（基本設計 §8.1 に項目なし） | `idempotency_record` が無限に増える |
| 3 | 監査アーカイブ（BAT-009）の実出力先（S3バケット・ストレージクラス・保持年数） | 現在はハッシュ算出のみのローカル実装。`LocalAuditArchiveAdapter` に `TODO(question)` |
| 4 | Outbox配送のSQS切替（現状は `InProcessOutboxDispatcher` によるアプリ内配送） | ADR候補。マルチインスタンス構成時に必要 |
| 5 | バッチの分散ロック（基本設計 §8.3「分散ロックまたはDBロック」） | 現在は対象行の `FOR UPDATE SKIP LOCKED` のみで多重起動に対応。ShedLock相当の導入要否 |
| 6 | ADR-003（BFF配置）/ ADR-004（決済非同期UI）/ ADR-005（Rich Text形式）が未起票 | 該当機能の着手時に必要 |

---

## 7. その他

- [ ] **git初回コミットが未実施**（mainブランチにコミットが1件もない）— 早急に実施推奨
- [ ] 設計書 `.docx` の再出力（`.md` は両書とも v1.2、`.docx` は v1.0 のまま）

---

## 8. 実装時の注意点（既知の落とし穴）

工程7・8で実際に踏んだもの。同種の実装を追加する際は注意。

| 項目 | 内容 |
|---|---|
| `@Transactional` の自己呼出し | `REQUIRES_NEW` は同一クラス内の呼出しではプロキシを経由せず**無効**になる。外部呼出しを挟む処理は `PaymentTransactionSteps` / `NotificationTransactionSteps` のように別Beanへ切り出す |
| `readOnly` トランザクション | `SELECT ... FOR UPDATE SKIP LOCKED` は読み取り専用トランザクションで**実行できない**（PostgreSQLがエラーを返す） |
| テストのHTTPクライアント | `RestTemplate()` の既定（HttpURLConnection）は**401応答の本文を破棄する**。エラーコードを検証するテストは `RestTemplate(JdkClientHttpRequestFactory())` を使う |
| テスト時のスケジューラ | Outbox配送・定期バッチは `application-test.yml` で無効化している。有効だと検証対象を裏で書き換えテストが不安定になる。バッチを検証するテストはUseCaseを直接呼ぶ |
| Hibernateスキーマ検証 | `ddl-auto: validate` のため、Migrationの型と Entity のマッピングが一致しないと起動に失敗する（`char(n)` は `bpchar` 扱いで不一致になる） |
