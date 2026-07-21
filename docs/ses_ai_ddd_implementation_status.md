# CF-Training 実装計画・実装状況・残タスク

- 対象リポジトリ: `F:\11\CF`
- 上位文書: 要件定義・技術選定 / 基本設計 BD-CF-001 / 詳細設計 DD-CF-001（`G:\マイドライブ\CF\`）
- 作成日: 2026-07-20（第2段階 工程5〜7 完了時点で更新）
- 本書は詳細設計書 §16.2「実装順序」を基準に、実装済み範囲と残タスクを整理したもの。

---

## 1. 実装計画（詳細設計 §16.2 実装順序）

| # | 工程 | 状態 |
|---|---|---|
| 1 | Shared Kernel、Error、Clock、ID、Money | ✅ 完了 |
| 2 | Project集約とDomain Unit Test | ✅ 完了 |
| 3 | Project Repository / Flyway / API / 画面 | ✅ 完了（SCR-010/011/020〜023を実機確認） |
| 4 | Review集約と審査フロー | ✅ 完了 |
| 5 | File / S3 Adapter | ✅ 完了 |
| 6 | Funding / Payment の内部モデルと冪等性 | ✅ 完了 |
| 7 | Payment Sandbox / Webhook / Outbox配送 | ✅ 完了 |
| 8 | Notification / Refund / Batch | ✅ 完了 |
| 9 | Identity / Admin / Audit（API公開） | ✅ 完了（Cognito連携含む。JIT自動登録の要判断あり） |
| 10 | 監視、CI/CD、E2E、運用手順 | ⬜ 未着手 |

第1段階（1〜4）に加え、第2段階の工程5〜9が完了。残るは工程10とフロントエンド。

---

## 2. 実装済み部分（第1段階）

### 2.1 Backend — Shared Kernel / 共通基盤

| 項目 | 実装 |
|---|---|
| 型付きID + ULID採番 | `shared/kernel/id/Identifiers.kt`, `UlidGenerator.kt` |
| Money（金額VO） | `shared/kernel/money/Money.kt` |
| Clock注入・DateRange | `shared/kernel/time/DateRange.kt`（`Instant.now()` 直接呼出し禁止規約） |
| 例外階層 | `shared/kernel/error/Exceptions.kt` |
| ドメインイベント共通形式 | `shared/kernel/event/DomainEvent.kt` |
| Result / Page 共通型 | `shared/kernel/CommonTypes.kt` |
| APIエンベロープ / RFC 9457 Problem Details | `shared/web/ApiEnvelope.kt`, `GlobalExceptionHandler.kt` |
| 相関ID | `shared/web/CorrelationIdFilter.kt` |
| 認証（localプロファイル） | `config/SecurityConfig.kt` — 開発用ヘッダー認証（`X-Dev-User` / `X-Dev-Roles`）+ `DevUserSeeder` |
| Transactional Outbox（書込側） | `shared/outbox/OutboxAppendPort.kt`, `OutboxPersistenceAdapter.kt` |
| 監査ログ記録 | `audit/`（Java実装: JPA Entity / Repository / Adapter） |

### 2.2 Backend — Project コンテキスト

- 集約: `Project` / `RewardPlan` / `ProjectStatus` / 各種VO（`project/domain/model/`）
- 募集終了イベントは `ProjectSucceeded` / `ProjectFailed` に分割（共通型 `ProjectFundingResult`。基本設計 §4.6）
- ドメインサービス: `ProjectSubmissionPolicy`（審査申請条件）
- ユースケース: 作成・更新・審査申請・取消・承認・公開・終了判定
- API: **API-PJ-001〜006**（`PublicProjectController` / `OwnerProjectController`）
- 永続化: `ProjectPersistenceAdapter`（JPA EntityとDomain Model分離、ADR-002方針）

### 2.3 Backend — Review コンテキスト

- 集約: `ReviewRequest`（開始・承認・差戻し・却下）
- API: **API-RV-001〜006**（`ReviewController`）

### 2.4 Backend — File コンテキスト（工程5）

- 集約: `FileObject`（issueUpload / completeUpload / delete）+ `Sha256` VO
- API: **API-FL-001/002**（`FileController`）— 許可MIME・10MB上限・SHA-256照合・5分URL期限
- S3 Port: `FileStoragePort` — local/testは `StubFileStorageAdapter`、dev以上は `S3FileStorageAdapter`（AWS SDK v2、ADR-006）
- 第1段階の `StubFileReferenceQuery` を `FilePersistenceAdapter` の実検証へ置換（COMPLETE状態＋所有者を実際に確認）

### 2.5 Backend — Funding コンテキスト（工程6）

- 集約: `Support` / `SupportItem` — 状態は基本設計 §3.5 の9種（PENDING / PAID / PAYMENT_FAILED / CANCEL_REQUESTED / CANCELLED / REFUND_REQUESTED / REFUNDING / REFUNDED / REFUND_FAILED）
- ドメインサービス: `FundingEligibilityPolicy`（PUBLISHED・期間内・会員ACTIVE）
- API: **API-FD-001〜004**（`SupportController`）
- 冪等性: `IdempotencyPort` / `IdempotencyPersistenceAdapter`（scope＋actor＋key、request_hash相違は409）
- 数量予約: `reward_plan` の条件付きUPDATE（§4.3.1）＋version競合時1回再試行、在庫切れは409 REWARD_SOLD_OUT

### 2.6 Backend — Payment コンテキスト（工程7）

- 集約: `Payment`（CREATED→PROCESSING→SUCCEEDED/FAILED/UNKNOWN→返金系、§4.4の遷移表どおり）
- 決済Sandbox: `SandboxPaymentGatewayAdapter`（HMAC-SHA256署名検証・5分許容差、実決済事業者へ非接続）
- API: **API-PY-001** Webhook（`PaymentWebhookController`）— 署名不正401、正常・重複とも204
- 受信履歴: `payment_webhook_event`（外部event_id主キーで重複排除、payload_hash相違はERROR記録）
- **Outbox配送Worker（BAT-006）**: `OutboxWorker` — `FOR UPDATE SKIP LOCKED` で50件/Tx、指数Backoff（1m/5m/15m/1h/6h）、上限5回でERROR固定
- 配送先: `InProcessOutboxDispatcher`（ApplicationEvent）→ `PaymentRequestedHandler` が決済開始（§5.3.1: 支援Txの外で外部呼出し）

### 2.7 コンテキスト間の公開契約

テーブル直接参照を避けるため、以下を新設（基本設計 §4.2）:

| 契約 | 提供元 | 用途 |
|---|---|---|
| `ProjectReferenceQuery`（拡張） | Project | 支援可否・リターン参照・数量予約 |
| `PaymentReferenceQuery` | Payment | 支援一覧・詳細での決済状態表示 |
| `SupportPaymentResultPort` | Funding | Webhookからの支援確定・失敗反映 |
| `UserReferenceQuery` | Identity | 会員ACTIVE判定（Java実装） |
| `FileReferenceQuery` | File | メイン画像のCOMPLETE・所有者確認 |

### 2.8 DB（Flyway）

適用済みMigration（V202607200001〜0007）:

| Migration | テーブル |
|---|---|
| 0001 identity | app_user / role / user_role |
| 0002 project | project / reward_plan / project_status_history |
| 0003 review | review_request / review_history |
| 0004 outbox_audit | outbox_event / audit_log / ai_activity_log / idempotency_record |
| 0005 file | file_object |
| 0006 funding_payment | support / support_item / payment |
| 0007 payment_webhook | payment_webhook_event |

### 2.9 テスト

- 単体: `ProjectTest` / `ReviewRequestTest` / `SharedKernelTest` / `FileObjectTest` / `SupportTest` / `PaymentTest` / `SandboxWebhookVerificationTest`
- アーキテクチャ: `ArchitectureTest`（ArchUnit — 依存方向・domain層純粋性・コンテキスト間の公開契約遵守）
- 統合（Testcontainers PostgreSQL）: `ProjectReviewFlowIntegrationTest` / `FileUploadFlowIntegrationTest` / `SupportFlowIntegrationTest` / `PaymentWebhookFlowIntegrationTest`

### 2.10 Frontend

- Next.js 16（App Router）の骨組み: `layout.tsx` / `page.tsx` / `globals.css`
- **SCR-010 プロジェクト検索**（`projects/page.tsx`、Server Component + BFF fetch `lib/backend.ts`）
- **SCR-011 プロジェクト詳細**（`projects/[projectId]/page.tsx`）
- **SCR-020〜023 起案者画面一式**（`owner/projects/`）: 一覧・編集（新規作成共通）・プレビュー・
  審査申請確認。React Hook Form + Zod、`useFieldArray`によるリターン可変長入力、
  Server Actions（`actions.ts`）によるBFF越しのAPI呼出し、メイン画像アップロード
  （SHA-256をブラウザで計算しAPI-FL-001/002を呼ぶ`MainImageUploader.tsx`）
- Docker Compose（PostgreSQL 18 + Mailpit）＋ `gradlew bootRun --spring.profiles.active=local` ＋
  `npm run dev` を実機起動し、作成→編集→プレビュー→審査申請確認までSSRページの実データ表示を確認済み
  （§3.3参照）

### 2.11 その他

- `infra/docker-compose.yml`（PostgreSQL 18）
- `docs/adr/ADR-0001-single-backend-project.md`（Gradle単一プロジェクト採用 = §16.4 ADR-001の判断）
- `AGENTS.md`（AIエージェント規約、DD §15.2準拠）/ `README.md`

---

## 3. 文書間の不整合と対応（基本設計を正として統一）

基本設計 BD-CF-001 を上位・正とし、詳細設計 DD-CF-001 との差異は以下のとおり統一した。
設計書（`.md`）は両書とも版数1.2へ更新済み。**`.docx` は未更新のため再出力が必要**。

| # | 差異 | 対応 |
|---|---|---|
| 1 | **支援状態の名称**: 基本設計 §3.5 は PENDING / PAID / REFUND_REQUESTED、詳細設計 §4.3 は PAYMENT_PENDING / CONFIRMED / REFUND_PENDING | ✅ 基本設計の9状態へ統一（`SupportStatus`）。詳細設計にのみ存在する AUTHORIZED は廃止し、決済保留中は PENDING を維持する（基本設計 §3.4「保留：PENDINGを維持して照会・再処理」）。基本設計にのみ存在する REFUNDING / REFUND_FAILED を追加し、`startRefund` / `failRefund` を実装 |
| 2 | **バッチID**: 基本設計 §8.1 は Outbox配送＝BAT-006・決済照合＝BAT-007、詳細設計 §9 は BAT-003・BAT-006 | ✅ 基本設計の番号へ統一（コード・設定のコメントを修正） |
| 3 | **ハッシュ列の型**: 詳細設計は `char(64)`（§8.13/§8.15/§8.19/§8.20/§8.21 の5列）、実装は全て `varchar(64)`。基本設計 §7.5 は元から `varchar(64)` | ✅ 詳細設計を `varchar(64)` へ修正（版数1.1）。PostgreSQLの`char(n)`は空白埋めで varchar に対する利点がなく、`ddl-auto: validate` が `bpchar` 不一致を検出するため。長さは `Sha256` VO で担保 |
| 4 | **ドメインイベント名**: 基本設計 §4.6 は SupportAccepted / PaymentCompleted / ProjectReviewRequested、実装と詳細設計 §4.1/§4.3/§4.4 は SupportRequested / PaymentSucceeded / ProjectSubmittedForReview | ✅ 基本設計 §4.6 を実装・詳細設計へ統一（版数1.1）。コードを基本設計に寄せると詳細設計3箇所も直す必要があり、基本設計 §4.6 のみの修正が最小の変更点となるため。**本原則（基本設計を正）の例外**として変更履歴に記録 |
| 5 | **`ProjectFailed` vs `ProjectFundingClosed`**: 基本設計 §4.6 は成立・不成立を別イベント、詳細設計 §4.1 と実装は `ProjectFundingClosed` に統合 | ✅ 基本設計どおり `ProjectSucceeded` / `ProjectFailed` へ分割（版数1.2）。共通型 `ProjectFundingResult` を導入。BAT-003 返金対象作成は `ProjectFailed` のみを購読すればよくなり、購読側のpayload解釈が不要になった |

### 3.1 解決済み: 401応答の本文欠落

当初「401応答でProblem Detailsの本文が空になる」と報告したが、**アプリケーション側の不具合ではなかった**。

- 原因は結合テストのHTTPクライアント。`RestTemplate()` の既定 `SimpleClientHttpRequestFactory`（`java.net.HttpURLConnection`）が、認証処理の一環として401応答の本文を破棄していた
- JDK標準 `java.net.http.HttpClient` で同一要求を送ると、サーバーは `application/problem+json` の本文（`code: PAYMENT_SIGNATURE_INVALID`）を正しく返していることを確認
- 対応: 結合テスト4クラスすべてで `RestTemplate(JdkClientHttpRequestFactory())` を使用。401応答の `code` も検証対象に含めた

---

## 3.2 工程9 — Identity / Admin / Audit（追加実装）

### Identity（Java、詳細設計 §2.2言語配置方針）

- Port分離: `AppUserPort` / `UserRolePort`（identity.application）を新設し、adapter実装
  （`AppUserRepository` / `UserRoleRepository`、JdbcTemplateベース）へ依存を逆転
  （ArchUnit `applicationMustNotDependOnAdapter` 違反の修正過程で導入）
- `ProfileService`: API-US-001（GET /me）/ API-US-002（PUT /me）
  - 楽観ロック（version）、メール重複は大文字小文字無視で判定（409 `EMAIL_ALREADY_USED`）
- `AdminUserService`: API-AD-001〜003
  - 会員検索（email/displayName部分一致、statusフィルタ）
  - ロール更新: 割当可能ロールは `role.assignable = true` をDB参照。自己のADMIN剥奪は403
    `ROLE_UPDATE_FORBIDDEN`（詳細設計UC-AD-001「自己権限剥奪検証」）
  - 会員停止: 自己停止は403 `USER_SUSPEND_FORBIDDEN`、停止済みへの再停止は409 `USER_INVALID_STATE`
- `CognitoJwtAuthenticationConverter`: Cognito Subject → 内部UserId変換、ロールはDB
  （user_role）を正として解決。未登録Subjectは初回アクセス時にJIT自動登録（既定ロールSUPPORTER）
  — `TODO(question)` 記載済み、dev投入前に承認要
- `ResourceServerSecurityConfig` へ組込み、`spring.security.oauth2.resourceserver.jwt.issuer-uri`
  を `COGNITO_ISSUER` 環境変数から注入（未設定時はdev以上で起動失敗＝意図的なフェイルファスト）

### Audit（Java）

- `AuditRecordPort` に `detail: Map<String,Object>` を受け取るデフォルトメソッドを追加
  （既存8引数メソッドへ後方互換で委譲。ロール変更理由・停止理由を記録するため）
- `AuditSearchQuery` / `AuditSearchPersistenceAdapter`: API-AU-001（監査ログ検索）/
  API-AU-002（AI利用記録検索）。from/to必須・最大31日、actionは完全一致のみ（前方一致不可）
- `AiActivityLogJpaEntity` / `AiActivityLogJpaRepository` を新設（ai_activity_logは
  詳細設計時点で記録側=AiActivityRecordPort実装が未着手のため検索のみ対応）

### Security

- `/api/v1/audit-logs`, `/api/v1/ai-activities` はADMIN/AUDITOR（`/admin/**` 配下ではないため
  個別ルールを追加）

### テスト

- `IdentityAdminAuditIntegrationTest`: プロフィール更新・会員検索・ロール更新・会員停止・
  監査ログ/AI利用記録検索の一連の結合テスト（自己保護ガード・楽観ロック競合・日付範囲検証を含む）
- ArchUnitへ `noCrossContextAdapterAccessFromIdentity` / `...FromAudit` を追加

---

## 3.3 解決済み: 公開プロジェクト検索（keyword未指定）が500になる

フロントエンドSCR-020系画面（起案者一式）を実機で動かして初めて発覚した不具合。
バックエンドの単体・結合テストは全て通過していたが、`GET /api/v1/projects`
（keyword未指定＝SCR-010の既定表示）を一度も自動テストで呼んでいなかったため検出できていなかった。

- 現象: `ERROR: operator does not exist: character varying ~~ bytea` でHTTP 500
- 原因: `ProjectJpaRepository.searchPublished`のJPQL
  `:keyword is null or p.title like concat('%', :keyword, '%')` で、keywordにnullを
  bindするとHibernateがconcat内のパラメータ型を推論できずbyteaとみなす。PostgreSQLはSQL全体を
  短絡評価前に型検査するため、`is null`分岐があっても`LIKE`側の型不一致でエラーになる
- 対応: `ProjectPersistenceAdapter.searchPublished`でkeywordを常に非null（未指定時は空文字列）
  で渡すよう変更し、JPQLの`is null`分岐を削除（`LIKE '%%'`は全件マッチするため挙動は変わらない）
- 再発防止: `ProjectReviewFlowIntegrationTest`にkeyword未指定の検索テストを追加

**教訓**: nullパラメータをJPQLの`LIKE`/`concat`へ渡す実装は、型推論に依存せず
呼出し側で非null値（空文字列等）に正規化してから渡す。自動テストのカバレッジを見直す際は
「クエリパラメータを省略した場合の既定動作」も対象に含めること。

---

## 3.4 【重大】.gitignoreが adapter.out.* 配下のソースを丸ごと除外していた（修正済み）

frontendの画面確認後、GitHubリポジトリの内容を検証して発覚。**pushされていたリポジトリは
クリーンチェックアウトではビルドできない状態**だった（ローカルの作業ツリーに対しては
`gradlew test`が通っていたため、pushまでこの問題に気付けなかった）。

- 原因: `.gitignore`の`out/`（IntelliJの既定出力先を想定したエントリ）が、先頭を固定していなかった
  ため**リポジトリ内のどの深さの`out`ディレクトリにもマッチ**した。本プロジェクトはヘキサゴナル
  アーキテクチャの規約で`adapter.out.persistence` / `adapter.out.storage` / `adapter.out.gateway` /
  `adapter.out.sender`というパッケージ名を全コンテキストで使っており、これらが軒並み除外されていた
- 影響範囲: project / review / funding / payment / notification / file / audit / identity の
  永続化・外部連携アダプタ **24ファイル**（テスト1件含む）が初回コミットから漏れていた
- 対応: `.gitignore`の該当行を`/out/`（リポジトリ直下のみ対象）へ修正し、漏れていた全ファイルを追加。
  修正後にクリーンクローンして`gradlew test`が通ることを確認してからpush
- **教訓**: `.gitignore`の一般的なエントリ（IDE出力先など）を追加する際は、先頭に`/`を付けて
  パス階層を固定しないと、同名のソースディレクトリ（本件の`out`のような一般的な単語）を
  意図せず除外し得る。`git add -A`が「正常に終了した」ことは「意図した全ファイルが追加された」
  ことを保証しないため、pushの前提として最低一度は**クリーンクローンでのビルド確認**を行うべき

---

## 4. 残タスク（工程8以降）

### 4.1 実装順序 8: Notification / Refund / Batch

- [ ] `Notification` / `Refund` 集約（notification / notification_delivery / refund テーブル）
- [ ] API-RF-001 返金要求 / API-RF-002 返金再実行
- [ ] SES Adapter（localはMock）
- [ ] バッチ（基本設計 §8.1 の番号）: BAT-001 公開開始 / BAT-002 募集終了 / BAT-003 返金対象作成（`ProjectFailed` を購読） / BAT-004 返金実行 / BAT-005 通知送信 / BAT-007 決済照合（API-PY-002含む） / BAT-008 ファイル清掃 / BAT-009 監査アーカイブ
  - BAT-006 Outbox配送は工程7で実装済み
  - 冪等記録の削除（詳細設計 §9 BAT-008相当）は基本設計 §8.1 に該当項目がないため要確認

### 4.2 実装順序 9: Identity / Admin / Audit（完了、詳細は §3.2）

- [x] API-US-001/002 プロフィール取得・更新
- [x] API-AD-001〜003 会員検索・ロール更新・会員停止
- [x] API-AU-001 監査ログ検索 / API-AU-002 AI利用記録検索
- [x] Cognito（OIDC）認証への切替（`CognitoJwtAuthenticationConverter`。実機User Poolでの確認は未実施）

### 4.3 実装順序 10: 監視・CI/CD・E2E・運用

- [ ] `.github/workflows/` CI構築（READMEに記載があるが**ディレクトリ未作成**）
- [ ] メトリクス・アラート（DD §12.5–12.6）
- [ ] E2Eテスト
- [ ] Terraform（ADR-007、`infra/` は現状docker-composeのみ）
- [ ] 運用手順書

### 4.4 Frontend 残画面（基本設計 §5.2 の19画面すべて完了）

REVIEWER / SUPPORTER / OPERATOR / ADMIN / 共通の全画面を実装済み。詳細は
`ses_ai_ddd_remaining_tasks.md` §5 を参照。

- ロール切替（SCR-001）はHttpOnly Cookieベースの開発用ログイン（`lib/devSession.ts`）で実現。
  本番はCognito OIDCへ置換。
- クライアント/サーバー境界: 型・定数を `lib/api-types.ts` に集約、`next/headers` 依存の
  `backendFetch` は `lib/backend.ts`（`server-only`）へ分離。
- OPERATOR（SCR-060/061）は運用者向け検索APIが未実装のためID指定アクションコンソールとして実装。
  検索API追加は残タスク（工程10 §4.0）。
- 実機で全ロール画面の表示・認可、起案→審査→公開→支援→履歴の一連フローを確認済み。

### 4.5 その他の気付き

- [ ] **git初回コミットが未実施**（mainブランチにコミットが1件もない）
- [ ] ADR-003（BFF配置）/ ADR-004（決済非同期UI）/ ADR-005（Rich Text形式）は未起票 — 該当機能の着手時に `docs/adr/` へ追加
- [ ] Outbox配送のSQS切替（現状は `InProcessOutboxDispatcher` によるアプリ内配送）はADR候補

---

## 5. 対応表: 実装済みAPI vs 未実装API

| 状態 | API |
|---|---|
| ✅ 実装済み | API-PJ-001〜006, API-RV-001〜006, API-FL-001〜002, API-FD-001〜004, API-PY-001〜002, API-RF-001〜002, API-US-001〜002, API-AD-001〜003, API-AU-001〜002 |
| ⬜ 未実装 | （バックエンドAPIはすべて実装済み。残るのはフロントエンド画面とCI/CD・運用基盤） |

---

## 6. ビルド・テスト結果（工程9完了時点）

```
cd backend && ./gradlew test   # BUILD SUCCESSFUL
```

- 単体・ArchUnit・統合テスト（Testcontainers PostgreSQL 18）すべて通過
- 統合テスト7クラス: プロジェクト審査フロー / ファイルアップロード / 支援申込 / 決済Webhook・Outbox配送 /
  工程8バッチ一気通貫 / 運用操作API / **Identity・Admin・Audit（工程9）**
- 結合テストの注意点（`src/test/resources/application-test.yml`）:
  - BAT-006 Outbox配送のスケジュール起動は無効。有効だと5秒ごとの配送が検証対象を裏で書き換え、実行タイミング次第でテストが失敗する。配送自体の検証は `OutboxWorker.publishBatch()` を明示的に呼ぶ
  - HTTPクライアントは `RestTemplate(JdkClientHttpRequestFactory())` を使用。既定の `HttpURLConnection` は401応答の本文を破棄するため、エラーコードを検証できない
