# CF-Training 残タスク一覧

- 対象リポジトリ: `F:\11\CF`
- 上位文書: 基本設計 BD-CF-001 v1.2 / 詳細設計 DD-CF-001 v1.2（`G:\マイドライブ\CF\`）
- 更新日: 2026-07-20（工程9 Identity/Admin/Audit完了時点）
- 実装済み範囲の詳細は `ses_ai_ddd_implementation_status.md` を参照。

---

## 1. 全体進捗（詳細設計 §16.2 実装順序）

| # | 工程 | 状態 |
|---|---|---|
| 1 | Shared Kernel、Error、Clock、ID、Money | ✅ 完了 |
| 2 | Project集約とDomain Unit Test | ✅ 完了 |
| 3 | Project Repository / Flyway / API / 画面 | ✅ 完了（SCR-010/011/020〜023） |
| 4 | Review集約と審査フロー | ✅ 完了 |
| 5 | File / S3 Adapter | ✅ 完了 |
| 6 | Funding / Payment の内部モデルと冪等性 | ✅ 完了 |
| 7 | Payment Sandbox / Webhook / Outbox配送 | ✅ 完了 |
| 8 | Notification / Refund / Batch | ✅ 完了（起案者向け通知の宛先解決のみ要判断） |
| 9 | Identity / Admin / Audit | ✅ 完了（Cognito連携はJIT自動登録の要判断あり） |
| 10 | 監視、CI/CD、E2E、運用手順 | ⬜ 未着手 |

バックエンドの業務フローは「起案 → 審査 → 公開 → 支援 → 決済 → 募集終了 → 返金 → 通知」まで
一気通貫で動作する。管理系API（会員・ロール・監査）もAPI-US/AD/AU全系列が揃い、
フロントエンドもProject関連一式（SCR-010/011/020〜023）が実機確認済み。
残るのはREVIEWER/SUPPORTER/OPERATOR/ADMIN向け画面とCI/CD・運用基盤（工程10）。

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

## 3. 工程9: Identity / Admin / Audit（完了）

- [x] API-US-001 プロフィール取得 / API-US-002 プロフィール更新（`MeController`）
- [x] API-AD-001 会員検索 / API-AD-002 ロール更新 / API-AD-003 会員停止（`AdminUserController`）
  - ロール更新は自己のADMIN剥奪を403 `ROLE_UPDATE_FORBIDDEN` で拒否
  - 会員停止は自己停止を403 `USER_SUSPEND_FORBIDDEN` で拒否、停止済みへの再停止は409 `USER_INVALID_STATE`
  - 割当可能ロールは `role.assignable = true` をDBから参照（ハードコードしない）
- [x] API-AU-001 監査ログ検索 / API-AU-002 AI利用記録検索（`AuditController`）
  - from/to必須・最大31日（超過は400 `DATE_RANGE_TOO_LARGE`）、actionは完全一致のみ
  - 認可はADMIN/AUDITOR（`/admin/**`配下ではないためSecurityConfigに個別ルールを追加）
- [x] **Cognito（OIDC）認証への切替**
  - `CognitoJwtAuthenticationConverter`（Java）でCognito Subject → 内部UserId変換、
    ロールはトークンではなくDB（user_role）を正として解決（基本設計 §9.1）
  - `spring.security.oauth2.resourceserver.jwt.issuer-uri` を `COGNITO_ISSUER` 環境変数から注入
  - **要判断（`TODO(question)`記載済み）**: 未登録Cognito Subjectの初回アクセス時にJIT自動登録
    （既定ロールSUPPORTER）する実装としている。誰でもSignUpで即時登録される動作を許容するかは
    dev投入前に承認者の判断が必要。許容しない場合は管理者Invite方式へ変更する
  - 実機Cognito User Poolでの結合確認は未実施（テストはlocal/testの開発用ヘッダー認証のみ）

Port分離: `AppUserPort` / `UserRolePort`（identity.application）を新設し、
JdbcTemplateベースの実装（`AppUserRepository` / `UserRoleRepository`）をadapter側に配置。
ArchUnitへ `noCrossContextAdapterAccessFromIdentity` / `...FromAudit` を追加済み。

`AuditRecordPort` は後方互換な形で `detail: Map<String,Object>` を受け取るデフォルトメソッドを追加し、
ロール変更理由・会員停止理由を監査ログのdetailへ記録できるようにした（既存呼出し元は変更不要）。

---

## 4. 工程10: 監視・CI/CD・E2E・運用（優先度: 中）

- [ ] **`.github/workflows/` のCI構築** — READMEに記載があるがディレクトリ自体が未作成
- [ ] メトリクス・アラート（詳細設計 §12.5–12.6、§9.3 バッチ監視）
  - `outbox_pending_count` / `oldest_outbox_age` / `notification_failure_rate` / `refund_retry_count` / `batch_last_success_age`
- [ ] E2Eテスト
- [ ] Terraform（ADR-007）— `infra/` は現状 docker-compose のみ
- [ ] 運用手順書（バッチ再実行、返金の手動対応、障害時の切り分け）

---

## 5. フロントエンド（優先度: 高）

基本設計 §5.2 の19画面のうち**実装済みは SCR-010/011/020〜023 の6画面**（Project関連一式）。

| 区分 | 状態 |
|---|---|
| 公開 | ✅ SCR-010 プロジェクト検索 / ✅ SCR-011 プロジェクト詳細 |
| OWNER | ✅ SCR-020 一覧 / ✅ SCR-021 編集（新規作成含む） / ✅ SCR-022 プレビュー / ✅ SCR-023 審査申請確認 |
| REVIEWER | ⬜ SCR-030 審査一覧 / SCR-031 審査詳細 |
| SUPPORTER | ⬜ SCR-040 支援入力 / SCR-041 支援確認 / SCR-042 支援結果 / SCR-051 支援履歴 |
| OPERATOR | ⬜ SCR-060 支援管理 / SCR-061 返金管理 |
| ADMIN | ⬜ SCR-070 会員・ロール管理 / SCR-071 監査ログ検索 |
| 共通 | ⬜ SCR-001 ログイン / SCR-002 アクセス拒否 / SCR-050 マイページ / SCR-080 システムエラー |

バックエンドAPIが揃っているため、SCR-030〜031 / SCR-040〜042 / SCR-051 はすぐに着手できる。
SCR-060/061（OPERATOR）は §2 の返金APIが前提（実装済み）。

### 5.1 実装済み画面の構成（Project一式）

- `frontend/src/app/projects/page.tsx`（SCR-010）/ `projects/[projectId]/page.tsx`（SCR-011）
- `frontend/src/app/owner/projects/page.tsx`（SCR-020）
- `frontend/src/app/owner/projects/new/page.tsx`・`[projectId]/edit/page.tsx`（SCR-021、作成/更新共通の
  `ProjectForm.tsx` をClient Componentとして共有。React Hook Form + Zod、`useFieldArray`でリターン可変長）
- `frontend/src/app/owner/projects/MainImageUploader.tsx`（メイン画像アップロード。ブラウザでSHA-256計算→
  API-FL-001発行→API-FL-002完了。local/testのS3スタブは発行時点で完了扱いのため実PUTは行わない仕様
  — backendの結合テストと同じ挙動）
- `frontend/src/app/owner/projects/[projectId]/preview/page.tsx`（SCR-022）
- `frontend/src/app/owner/projects/[projectId]/submit-review/page.tsx` + `SubmitReviewForm.tsx`（SCR-023）
- `frontend/src/app/owner/projects/actions.ts`（Server Actions。create/update/submitForReview/cancel/
  issueUpload/completeUploadをBFF側で実行し、認証ヘッダーをブラウザへ渡さない §7.9）
- 起案者向けの単体取得APIが無いため、詳細取得は所有者判定込みの`PublicProjectController`
  （`GET /api/v1/projects/{id}`）を共用している

### 5.2 実機確認で見つけたバックエンドの不具合（本作業で修正済み）

`GET /api/v1/projects`（keyword未指定、すなわちSCR-010の既定表示）で
`ERROR: operator does not exist: character varying ~~ bytea` が発生し500になっていた。

- 原因: JPQLの`:keyword is null or p.title like concat('%', :keyword, '%')`で、keywordにnullを
  bindするとHibernateがconcat内のパラメータ型を推論できずbyteaとみなし、PostgreSQLの型検査で失敗する
  （SQLは短絡評価前に全体を型検査するため、`is null`分岐があっても影響を受ける）
- 修正: keywordを常に非null（未指定時は空文字列）で渡すよう`ProjectPersistenceAdapter.searchPublished`
  を変更し、JPQLの`is null`分岐を削除（`LIKE '%%'`は全件マッチするため動作は変わらない）
- 既存の結合テストではこのAPIを一度も呼んでいなかったため検出できていなかった。
  回帰防止として`ProjectReviewFlowIntegrationTest`にkeyword未指定の検索テストを追加した
- **教訓**: バックエンドの単体・結合テストが全て通過していても、フロントエンドから実際に叩いて
  初めて気づくクラスの不具合がある（本件はnullパラメータのSQL型推論というテストで書き漏らしやすい観点）

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
| 7 | 未登録Cognito Subjectの初回アクセス時JIT自動登録（既定ロールSUPPORTER）を許容するか | `CognitoJwtAuthenticationConverter` に `TODO(question)`。許容しない場合は管理者Invite方式へ変更 |

---

## 7. その他

- [x] git初回コミット・push実施済み（`https://github.com/rice-3/cf.git` の `main` ブランチ）
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
