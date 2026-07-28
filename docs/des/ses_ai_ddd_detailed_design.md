# クラウドファンディング型教育・実践開発システム

# 詳細設計書

| 項目 | 内容 |
| --- | --- |
| 文書ID | DD-CF-001 |
| 版数 | 1.3 |
| 作成日 | 2026-07-20 |
| 文書状態 | 実装開始用・暫定確定版 |
| 上位文書 | 要件定義書・要件確認書・技術選定書、基本設計書 BD-CF-001 |
| 対象構成 | Amazon Corretto 25 / Kotlin・Java / Spring Boot 4.1 / DDD / Next.js 16 / AWS / AI駆動開発 |
| 想定読者 | 実装者、レビュー担当者、試験担当者、教育担当者、運用担当者 |

# 0. 文書管理

## 0.1 目的

本書は、基本設計書で定義した画面、API、データ、ドメイン、非同期処理、セキュリティおよび運用方式を、実装者がクラス・関数・SQL・設定・試験へ直接展開できる粒度まで具体化する。

- モジュール、パッケージ、クラス、インターフェース、依存方向を定義する。
- 主要ユースケースについて、入力、認可、検証、トランザクション、永続化、イベント、戻り値、例外を定義する。
- 画面項目、API入出力、DB列、索引、制約、バッチ処理を対応付ける。
- AIコーディングエージェントが変更してよい範囲、完了条件、レビュー観点を明示する。
- 実装と単体・結合・E2E試験のトレーサビリティを確保する。

## 0.2 適用範囲

対象は教育用クラウドファンディング型Webシステム CF-Training の初期リリースである。決済はSandboxまたはモックを使用し、実在顧客情報、実決済情報、秘密情報は扱わない。

## 0.3 前提・制約

| ID | 前提・制約 |
| --- | --- |
| D-A01 | JVMはAmazon Corretto 25に統一し、Java/KotlinのJVMターゲットを25で一致させる。 |
| D-A02 | Spring Boot 4.1系、Spring Framework 7系、Gradle 9.1以上を使用する。 |
| D-A03 | 初期構成はモジュラーモノリスとし、モジュール間のDB直接参照を禁止する。 |
| D-A04 | Kotlinを主言語、Javaを副言語とする。同一集約内部の主言語は統一する。 |
| D-A05 | 更新系はJPA、複雑な参照系はMyBatisを基本とする。 |
| D-A06 | イベントの確実な外部配送にはTransactional Outboxを使用する。 |
| D-A07 | AI生成物は人間レビュー、CI、セキュリティ検査を通過するまで採用しない。 |
| D-A08 | 本書と実装が矛盾する場合、変更理由をADRに記録して設計書を更新する。 |

## 0.4 変更履歴

| 版数 | 日付 | 変更内容 |
| --- | --- | --- |
| 1.0 | 2026-07-20 | 初版。クラス、API、DB、シーケンス、例外、バッチ、テスト、AI統制を定義。 |
| 1.1 | 2026-07-20 | §4.3 支援状態を基本設計 §3.5 へ統一（PAYMENT_PENDING→PENDING、CONFIRMED→PAID、REFUND_PENDING→REFUND_REQUESTED、AUTHORIZED廃止、REFUNDING/REFUND_FAILED追加）。§5.1/§5.3 の該当記述も更新。ハッシュ列の型を char(64)→varchar(64) へ統一（§8.13/§8.15/§8.19/§8.20/§8.21。基本設計 §7.5 およびHibernateスキーマ検証に合わせた）。 |
| 1.2 | 2026-07-20 | §4.1 の `ProjectFundingClosed` を `ProjectSucceeded` / `ProjectFailed` へ分割（基本設計 §4.6・§8.1 に合わせ、購読側がイベント種別のみで振り分けられるようにするため）。両者の共通型として `ProjectFundingResult` を定義。 |
| 1.3 | 2026-07-28 | **実装を正として同期**（コード読解による突き合わせ）。§1.5 モジュール構成を単一Backendプロジェクトの実態へ更新（ADR-0001。`app-worker` は存在しない）。§9 バッチ表のIDを**基本設計 §8.1 の採番へ振り直し**（実装の `@SchedulerLock` 名に一致）、決済照合の周期を 10分→**15分**へ修正、Outbox配送先をアプリ内Handlerへ確定（ADR-0008）、ShedLockの適用有無を明記（ADR-0003）、実装済みの **BAT-003 返金対象作成**と **BAT-010 冪等記録削除**を追加。§9.2以降のSQS前提の記述を実態へ修正。 |

## 0.5 章構成

1. 実装共通規約
2. モジュール・パッケージ設計
3. 共通部品設計
4. ドメインクラス設計
5. アプリケーションユースケース設計
6. API詳細設計
7. 画面詳細設計
8. データベース詳細設計
9. バッチ・非同期処理詳細設計
10. 外部インターフェース詳細設計
11. セキュリティ詳細設計
12. 例外・ログ・監視詳細設計
13. 構成・デプロイ詳細設計
14. テスト詳細設計
15. AI駆動開発詳細設計
16. トレーサビリティ・実装引継ぎ


# 1. 実装共通規約

## 1.1 使用言語・バージョン

| 区分 | 技術 | 設計上の扱い |
| --- | --- | --- |
| JDK | Amazon Corretto 25 | 開発・CI・コンテナ・本番相当で統一する。Preview機能は原則禁止。 |
| Kotlin | JVM 25対応版 | 新規ドメイン、アプリケーションサービス、テストの主言語。 |
| Java | Java 25 | Identity、外部連携、既存Java資産を想定する演習に使用。 |
| Backend | Spring Boot 4.1 / Spring Framework 7 | Web、Security、Validation、Data、Observabilityを利用。 |
| Build | Gradle 9.1以上 / Kotlin DSL | Wrapper必須。ローカルGradleへ依存しない。 |
| Frontend | Node.js 24 LTS / TypeScript / React 19.2 / Next.js 16.2 | App Router、Server Componentを基本とする。 |
| DB | PostgreSQL 18 | timestamptz、bigint、jsonbを利用。DDLはFlyway管理。 |

## 1.2 命名規約

| 対象 | 規約 | 例 |
| --- | --- | --- |
| Kotlin/Java package | 小文字、コンテキスト単位 | com.example.cf.project.domain.model |
| Class/Interface | PascalCase | Project, SubmitProjectForReviewUseCase |
| Function/Method | lowerCamelCase、動詞開始 | submitForReview(), findPublishedProjects() |
| Command | 動作＋対象＋Command | CreateProjectCommand |
| Query | 取得内容＋Query | SearchPublishedProjectsQuery |
| DTO | 用途＋Request/Response | CreateProjectRequest |
| DB table/column | snake_case、単数名 | project, owner_user_id |
| API path | 複数形名詞、動作はサブリソース | /owner/projects/{id}/review-requests |
| Event | 過去形 | ProjectSubmittedForReview |
| Error code | 領域_内容 | PROJECT_INVALID_STATE |

## 1.3 Null・型・金額・日時

- ドメイン層では意味のある不在のみnullableとし、未検証入力をnullableのまま持ち込まない。
- 金額は `Long` の円単位を直接持たず、`Money` Value Objectで保持する。
- IDは文字列を直接渡さず、`ProjectId`、`SupportId` 等の型で包む。
- 日時はドメイン・アプリケーション層で `Instant` を基本とし、画面表示時にタイムゾーン変換する。
- 現在時刻は `Clock` を注入し、`Instant.now()` の直接呼出しを禁止する。
- Java/Kotlin境界にはNullability Annotationまたは明示的変換を置く。

## 1.4 コーディング制約

- ControllerからRepositoryを直接呼び出さない。
- EntityをAPIレスポンスへ直接シリアライズしない。
- ドメイン層へSpring、JPA、HTTP、AWS SDKの型を持ち込まない。
- トランザクション境界はApplication Serviceに置く。
- 業務例外をcatchして握りつぶさない。
- ログへアクセストークン、Cookie、決済トークン、メール本文、個人情報を出力しない。
- AI生成コードであっても、公開API、DB、イベント、セキュリティ変更には設計更新を必須とする。

## 1.5 Gradleモジュール構成

**実装は単一Backendプロジェクト構成を採用している（ADR-0001）。** 本節末尾の但し書きに沿った選択で、
コンテキスト境界はGradleモジュールではなくパッケージで表現し、依存規則はArchUnitで検査する。

```text
root
├─ backend                 # Spring Boot起動 + Web API + Batch（Gradleプロジェクト名: cf-training-backend）
│  └─ src/main/{kotlin,java}/com/example/cf/
│     ├─ identity/  project/  review/  funding/
│     ├─ payment/  notification/  file/  audit/
│     ├─ shared/           # Shared Kernel（kernel / outbox / batch / web / observability など）
│     └─ config/
├─ frontend                # Next.js（Web + BFF、ADR-0004）
└─ infra                   # Terraform / docker-compose
```

各コンテキストは `adapter / application / domain` の3層で構成する。
Batchは独立プロセスではなく `adapter/in/batch` として同一プロセスに置く
（Outbox配送もアプリ内Handlerへ配送する。ADR-0008）。

> 当初案では `app-api` / `app-worker` とコンテキストごとの `module-*` に分けるGradleマルチプロジェクトを
> 想定していた。教育初期の負担を避けるため単一プロジェクトから開始し、依存規則はArchUnitで
> 同等に検査している（Spring Modulithは未導入）。

# 2. モジュール・パッケージ設計

## 2.1 モジュール依存

```text
app-api / app-worker
    ↓
application
    ↓
domain

adapter.in  → application
adapter.out → domain port / application port

domain ─X→ Spring / JPA / MyBatis / AWS SDK / HTTP
```

## 2.2 コンテキスト別パッケージ

| コンテキスト | ルートパッケージ | 主言語 | 公開する契約 |
| --- | --- | --- | --- |
| Identity | com.example.cf.identity | Java | CurrentUser, UserStatusQuery, RoleQuery |
| Project | com.example.cf.project | Kotlin | ProjectReferenceQuery, ProjectDomainEvent |
| Review | com.example.cf.review | Kotlin | ReviewDecisionResult, ReviewDomainEvent |
| Funding | com.example.cf.funding | Kotlin | SupportResult, FundingDomainEvent |
| Payment | com.example.cf.payment | Kotlin/Java | PaymentPort, RefundPort, PaymentDomainEvent |
| Notification | com.example.cf.notification | Java/Kotlin | NotificationRequest |
| File | com.example.cf.file | Java | FileReferenceQuery, PresignedUploadPort |
| Audit | com.example.cf.audit | Java | AuditRecordPort, AiActivityRecordPort |
| Shared Kernel | com.example.cf.shared | Kotlin | ID、Money、Clock、ProblemCode等の最小共有型 |

## 2.3 モジュール内部構造

```text
project
├─ domain
│  ├─ model
│  ├─ event
│  ├─ service
│  └─ repository       # Port
├─ application
│  ├─ command
│  ├─ query
│  ├─ usecase
│  └─ dto
├─ adapter
│  ├─ in/web
│  └─ out
│     ├─ persistence
│     └─ event
└─ config
```

## 2.4 依存許可表

| From / To | domain | application | adapter | 他コンテキスト内部 |
| --- | --- | --- | --- | --- |
| domain | 許可 | 禁止 | 禁止 | 禁止 |
| application | 許可 | 許可 | 禁止 | 公開契約のみ |
| adapter | 許可 | 許可 | 許可 | 公開契約のみ |
| config | 許可 | 許可 | 許可 | 公開契約のみ |

# 3. 共通部品設計

## 3.1 ID Value Object

```kotlin
@JvmInline
value class ProjectId(val value: String) {
    init { require(ULID_PATTERN.matches(value)) }
    companion object { fun newId(generator: UlidGenerator): ProjectId }
}
```

| 部品 | 責務 | 主な検証 |
| --- | --- | --- |
| ProjectId / SupportId等 | 型の取り違え防止 | ULID 26文字 |
| Money | 円金額の加減算・比較 | 0以上、上限値、通貨JPY固定 |
| DateRange | 開始・終了期間 | 開始＜終了、最大180日 |
| EmailAddress | メール形式 | 長さ、構文、正規化 |
| IdempotencyKey | 冪等キー | 1～100文字、許可文字 |
| Version | 楽観ロック版数 | 0以上 |

## 3.2 CurrentUser

```kotlin
data class CurrentUser(
    val userId: UserId,
    val roles: Set<RoleCode>,
    val authenticatedAt: Instant
) {
    fun has(role: RoleCode): Boolean = role in roles
}
```

## 3.3 Clock・ID採番

| Interface | 実装 | 用途 |
| --- | --- | --- |
| SystemClock | UTC Clock | 本番・通常試験 |
| FixedClock | 固定時刻 | 単体・結合試験 |
| UlidGenerator | 単調増加ULID | 業務ID・イベントID |
| CorrelationIdGenerator | UUID/ULID | ログ・API追跡 |

## 3.4 Result/Page共通型

```kotlin
data class PageResult<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)
```

## 3.5 共通監査情報

```kotlin
data class AuditContext(
    val actorUserId: UserId?,
    val correlationId: CorrelationId,
    val source: AuditSource,
    val clientIpHash: String?
)
```


# 4. ドメインクラス設計

## 4.1 Project集約

| 項目 | 設計 |
| --- | --- |
| Aggregate Root | Project |
| 主キー | ProjectId |
| 所有者 | OwnerUserId |
| 構成要素 | ProjectTitle, ProjectSummary, ProjectBody, FundingCondition, RewardPlan, ProjectStatus, Version |
| Repository | ProjectRepository |
| 主要イベント | ProjectCreated, ProjectSubmittedForReview, ProjectCancelled, ProjectApproved, ProjectPublished, ProjectSucceeded, ProjectFailed |

### 4.1.1 Projectプロパティ

| プロパティ | 型 | 可変 | 制約 |
| --- | --- | --- | --- |
| id | ProjectId | 不可 | 採番済みULID |
| ownerUserId | UserId | 不可 | 作成者と一致 |
| title | ProjectTitle | 可 | 1～100文字 |
| summary | ProjectSummary | 可 | 1～300文字 |
| body | ProjectBody | 可 | 1～20,000文字 |
| fundingCondition | FundingCondition | 可 | 金額・方式・期間 |
| rewardPlans | List<RewardPlan> | 可 | 申請時1件以上 |
| status | ProjectStatus | 可 | 状態遷移表に従う |
| mainFileId | FileId? | 可 | 申請時必須 |
| version | Version | 可 | 更新ごとに増分 |

### 4.1.2 Projectメソッド

| メソッド | 入力 | 事前条件 | 結果・イベント |
| --- | --- | --- | --- |
| create | owner, draft values | OWNERである | DRAFTで生成、ProjectCreated |
| updateDraft | editable values, expectedVersion | DRAFTまたはRETURNED、所有者一致 | 内容更新 |
| submitForReview | actor, now | 必須項目充足、所有者一致 | REVIEW_REQUESTED、ProjectSubmittedForReview |
| cancel | actor, reason | DRAFT/RETURNED/APPROVED | CANCELLED、ProjectCancelled |
| approve | reviewer, reviewId | UNDER_REVIEW | APPROVED、ProjectApproved |
| publish | now | APPROVEDかつ開始日時到達 | PUBLISHED、ProjectPublished |
| closeFunding | now, raisedAmount | PUBLISHEDかつ終了日時到達 | SUCCEEDEDならProjectSucceeded、FAILEDならProjectFailed（共通型 `ProjectFundingResult`） |

### 4.1.3 Project不変条件

- 募集終了日時は開始日時より後で、期間は180日以内である。
- 目標金額は1,000円以上100,000,000円以下である。
- 審査申請時にタイトル、概要、本文、メイン画像、1件以上のリターンが存在する。
- 公開後に目標金額、募集方式、募集期間を変更できない。
- 支援受付はPUBLISHEDかつ募集期間内の場合だけ可能である。
- 楽観ロック版数が一致しない更新は拒否する。

## 4.2 ReviewRequest集約

| 項目 | 設計 |
| --- | --- |
| Aggregate Root | ReviewRequest |
| 主キー | ReviewId |
| 参照 | ProjectId |
| 構成要素 | ReviewStatus, ReviewerUserId?, ReviewChecklist, ReviewComment, ReviewHistory |
| 主要イベント | ReviewStarted, ProjectApproved, ProjectReturned, ProjectRejected |

| メソッド | 事前条件 | 状態遷移 |
| --- | --- | --- |
| start(reviewer) | REQUESTED、REVIEWER権限 | UNDER_REVIEW |
| approve(checklist) | UNDER_REVIEW、必須チェック完了 | APPROVED |
| returnForCorrection(comment) | UNDER_REVIEW、コメント必須 | RETURNED |
| reject(reason, comment) | UNDER_REVIEW、理由必須 | REJECTED |

## 4.3 Support集約

| 項目 | 設計 |
| --- | --- |
| Aggregate Root | Support |
| 主キー | SupportId |
| 参照 | ProjectId, SupporterUserId, RewardPlanId? |
| 構成要素 | Money, SupportStatus, IdempotencyKey, PaymentId?, Version |
| 主要イベント | SupportRequested, SupportConfirmed, SupportPaymentFailed, SupportCancelled, RefundRequired |

| メソッド | 事前条件 | 結果 |
| --- | --- | --- |
| request | プロジェクト支援可能、金額・数量有効 | PENDING |
| confirm | PENDING、決済成功 | PAID |
| failPayment | PENDING、決済失敗 | PAYMENT_FAILED |
| cancel | 取消期限内、未精算 | CANCEL_REQUESTEDまたはCANCELLED |
| requireRefund | 成立条件不充足等 | REFUND_REQUESTED |
| startRefund | REFUND_REQUESTED/REFUND_FAILED | REFUNDING |
| markRefunded | REFUNDING | REFUNDED |
| failRefund | REFUNDING | REFUND_FAILED |

支援状態は基本設計 §3.5 を正とする（版数1.1で統一）。決済オーソリ済みを表す独立状態は設けず、
決済保留中はPENDINGを維持する（基本設計 §3.4「保留：PENDINGを維持して照会・再処理」）。

### 4.3.1 支援数量予約

数量上限付きリターンは、支援作成トランザクション内で `reward_plan.reserved_quantity` を条件付きUPDATEする。更新件数0件の場合は在庫不足として支援を作成しない。

```sql
UPDATE reward_plan
   SET reserved_quantity = reserved_quantity + :quantity,
       version = version + 1
 WHERE reward_plan_id = :rewardPlanId
   AND version = :expectedVersion
   AND (quantity_limit IS NULL
        OR reserved_quantity + :quantity <= quantity_limit);
```

## 4.4 Payment集約

| 項目 | 設計 |
| --- | --- |
| Aggregate Root | Payment |
| 主キー | PaymentId |
| 外部識別子 | ProviderPaymentId? |
| 構成要素 | PaymentStatus, Amount, Provider, FailureReason?, Version |
| 主要イベント | PaymentRequested, PaymentSucceeded, PaymentFailed, PaymentReconciliationRequired |

| 状態 | 説明 | 許可遷移 |
| --- | --- | --- |
| CREATED | 内部生成済み | PROCESSING |
| PROCESSING | 外部決済処理中 | SUCCEEDED/FAILED/UNKNOWN |
| SUCCEEDED | 決済成功 | REFUND_PENDING |
| FAILED | 決済失敗 | 終端 |
| UNKNOWN | 結果不明 | SUCCEEDED/FAILED |
| REFUND_PENDING | 返金要求済み | REFUNDED/REFUND_FAILED |
| REFUNDED | 返金済み | 終端 |
| REFUND_FAILED | 返金失敗 | REFUND_PENDING |

## 4.5 Refund集約

| メソッド | 事前条件 | 結果 |
| --- | --- | --- |
| request | Payment=SUCCEEDED、未返金 | REQUESTED |
| start | REQUESTED/RETRY_WAIT | PROCESSING |
| succeed | PROCESSING | SUCCEEDED |
| fail | PROCESSING | RETRY_WAITまたはFAILED |
| retry | RETRY_WAIT、回数上限未満 | REQUESTED |

## 4.6 Notification集約

| 項目 | 内容 |
| --- | --- |
| Aggregate Root | Notification |
| チャネル | EMAIL / IN_APP |
| 状態 | PENDING / SENDING / SENT / RETRY_WAIT / FAILED |
| 重複防止 | business_key＋channelに一意制約 |
| 本文 | テンプレートID＋変数。個人情報をログ出力しない。 |

## 4.7 FileObject集約

| メソッド | 検証 |
| --- | --- |
| issueUpload | 許可MIME、最大10MB、所有者、用途 |
| completeUpload | S3 HeadObject結果、サイズ、Content-Type、ハッシュ |
| attachToProject | 所有者一致、COMPLETE状態 |
| delete | 参照なしまたは削除猶予後 |

## 4.8 ドメインサービス

| サービス | 責務 | 入力・出力 |
| --- | --- | --- |
| ProjectSubmissionPolicy | 審査申請可能性の総合判定 | Project, FileReference, OwnerStatus → Violations |
| FundingEligibilityPolicy | 支援可能性の判定 | ProjectReference, Supporter, now → result |
| ProjectFundingResultCalculator | 成立/不成立判定 | type, target, raised → result |
| RefundEligibilityPolicy | 返金可否判定 | Support, Payment, reason → result |
| ReviewAssignmentPolicy | 審査担当割当候補 | ReviewRequest, reviewers → reviewer |

## 4.9 ドメインイベント共通形式

```kotlin
data class DomainEventEnvelope<T : DomainEvent>(
    val eventId: EventId,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val occurredAt: Instant,
    val correlationId: CorrelationId,
    val payload: T
)
```


# 5. アプリケーションユースケース設計

## 5.1 共通処理順

```text
1. CurrentUser / CorrelationId取得
2. Request DTO形式検証
3. ロール・所有権認可
4. 冪等性確認（対象APIのみ）
5. Aggregate / Read Model取得
6. 業務ルール検証・Domain Method実行
7. Aggregate保存
8. Outbox Event保存
9. Audit Log保存
10. Commit
11. Response DTO変換
```

7～9は同一DBトランザクションで実行する。外部HTTP、S3、SES、決済API呼出しは原則としてDBトランザクション外へ分離し、必要な場合はOutbox/Queueを使用する。

| UC ID | 名称 | 認可 | 入力 | 出力 | 主要処理 | 主な例外 |
| --- | --- | --- | --- | --- | --- | --- |
| UC-PJ-001 | プロジェクト下書き作成 | OWNER | CreateProjectCommand | ProjectId, status=DRAFT | Project.create → repository.save → audit | PROJECT_VALIDATION_ERROR |
| UC-PJ-002 | プロジェクト下書き更新 | OWNERかつ所有者 | UpdateProjectCommand | updatedAt, version | 取得 → version/状態確認 → updateDraft → save | PROJECT_NOT_FOUND / OPTIMISTIC_LOCK_CONFLICT |
| UC-PJ-003 | 審査申請 | OWNERかつ所有者 | SubmitProjectForReviewCommand | reviewId, status=REVIEW_REQUESTED | 提出ポリシー → Project遷移 → ReviewRequest生成 → Outbox | PROJECT_INCOMPLETE / PROJECT_INVALID_STATE |
| UC-RV-001 | 審査開始 | REVIEWER | StartReviewCommand | reviewStatus=UNDER_REVIEW | Review取得 → 担当者競合確認 → start → save | REVIEW_ALREADY_ASSIGNED |
| UC-RV-002 | 審査承認 | REVIEWERかつ担当者 | ApproveReviewCommand | projectStatus=APPROVED | Review.approve → Project.approve → 両方保存 → Outbox | REVIEW_CHECKLIST_INCOMPLETE |
| UC-RV-003 | 審査差戻し | REVIEWERかつ担当者 | ReturnReviewCommand | projectStatus=RETURNED | Review.return → Project.return → 通知Event | REVIEW_COMMENT_REQUIRED |
| UC-FD-001 | 支援申込 | SUPPORTER | CreateSupportCommand＋Idempotency-Key | supportId, PENDING | 冪等確認 → 支援可否 → 数量予約 → Support/Payment生成 → Outbox | PROJECT_NOT_SUPPORTABLE / REWARD_SOLD_OUT |
| UC-PY-001 | 決済Webhook処理 | 署名検証済 | PaymentWebhookCommand | HTTP 204 | 受信記録INSERT → 重複判定 → Payment/Support更新 → Outbox | PAYMENT_SIGNATURE_INVALID / PAYMENT_EVENT_CONFLICT |
| UC-FD-002 | 支援取消 | 所有者 | CancelSupportCommand | cancelStatus | 取消可否 → Payment状態確認 → Cancel/Refund Event | SUPPORT_CANNOT_CANCEL |
| UC-RF-001 | 返金要求 | OPERATOR | RequestRefundCommand | refundId | 返金可否 → Refund生成 → Outbox | REFUND_NOT_ALLOWED / REFUND_ALREADY_EXISTS |
| UC-FL-001 | 署名付きURL発行 | 認証済 | IssueUploadCommand | uploadUrl, fileId, expiresAt | MIME/size/用途検証 → FileObject作成 → URL発行 | FILE_TYPE_NOT_ALLOWED |
| UC-AD-001 | ロール更新 | ADMIN | UpdateUserRolesCommand | roles | 対象・自己権限剥奪検証 → 更新 → 監査 | ROLE_UPDATE_FORBIDDEN |

## 5.2 UC-PJ-003 審査申請詳細

| 段階 | 処理 | 設計詳細 |
| --- | --- | --- |
| 入力 | projectId, expectedVersion | Path/Bodyから取得。ULID形式、version 0以上。 |
| 認可 | OWNER＋所有者 | Project.ownerUserIdとCurrentUser.userIdを比較。 |
| 検証 | 状態・必須項目・ファイル | DRAFT/RETURNED、画像COMPLETE、リターン1件以上、期間・金額有効。 |
| 更新 | Project.submitForReview | ProjectをREVIEW_REQUESTEDへ変更。 |
| 生成 | ReviewRequest.create | reviewId採番、status=REQUESTED。 |
| 永続化 | Project/Review/Outbox/Audit | 同一トランザクション。 |
| 応答 | 202 Accepted | reviewId、projectStatus、submittedAt。 |
| 通知 | ProjectSubmittedForReview | Commit後、Outbox Workerが審査通知を生成。 |

### 5.2.1 シーケンス

```text
Owner -> API: POST /owner/projects/{id}/review-requests
API -> UseCase: submit(command, currentUser)
UseCase -> ProjectRepository: findByIdForUpdate(id)
UseCase -> FileReferenceQuery: verifyCompleted(mainFileId)
UseCase -> ProjectSubmissionPolicy: validate(project)
UseCase -> Project: submitForReview(now)
UseCase -> ReviewRepository: save(new ReviewRequest)
UseCase -> OutboxRepository: append(ProjectSubmittedForReview)
UseCase -> AuditPort: record(SUBMIT_REVIEW)
UseCase -> DB: commit
API --> Owner: 202 Accepted
```

## 5.3 UC-FD-001 支援申込詳細

| 段階 | 処理 | 設計詳細 |
| --- | --- | --- |
| 冪等性 | Idempotency-Key確認 | userId＋keyを一意とし、完了済みなら保存済み応答を返す。処理中なら409。 |
| 支援可否 | 公開・期間・利用者状態 | PUBLISHED、nowが期間内、会員ACTIVE。 |
| 金額 | 支援額・リターン額 | 1円単位、最低額以上、上限100,000,000円。 |
| 数量 | 条件付きUPDATE | 数量上限を超える場合409 REWARD_SOLD_OUT。 |
| 生成 | Support/Payment | Support=PENDING、Payment=CREATED。 |
| 永続化 | 5レコード | reward_plan、support、payment、idempotency_record、outbox_event。 |
| 外部処理 | 非同期決済開始 | Commit後にPaymentRequestedをWorkerが処理。 |
| 応答 | 202 Accepted | supportId、paymentStatus=PENDING、確認URL。 |

### 5.3.1 トランザクション

支援作成トランザクションで外部決済APIを呼び出さない。内部レコード確定後、Outbox Workerが決済APIを呼び、結果をPaymentへ反映する。同期決済UIが必要な場合も、外部呼出しの前後を短い別トランザクションに分ける。

## 5.4 UC-PY-001 Webhook詳細

| 段階 | 処理 |
| --- | --- |
| 1 | Raw bodyと署名ヘッダーを取得する。 |
| 2 | Provider固有署名を時刻許容幅付きで検証する。 |
| 3 | payment_webhook_eventへ外部event_idを主キーとしてINSERTする。重複キーなら処理済み結果を返す。 |
| 4 | payload_hashが既存値と異なる場合、改ざんまたはProvider異常としてERRORにする。 |
| 5 | provider_payment_idでPaymentをロック付き取得する。 |
| 6 | イベント種別と現在状態の組合せを検証する。 |
| 7 | Payment、Supportを状態遷移させ、OutboxとAuditを保存する。 |
| 8 | processed_atとPROCESSEDを更新してCommitする。 |
| 9 | 正常・重複ともHTTP 204を返す。署名不正のみ401、再試行可能な内部障害は500。 |

## 5.5 楽観ロック・排他

| 対象 | 方式 | 競合時 |
| --- | --- | --- |
| Project編集 | version列＋JPA @Version | 409 OPTIMISTIC_LOCK_CONFLICT、最新データ再取得 |
| Review割当 | UPDATE ... WHERE status=REQUESTED | 更新0件なら409 REVIEW_ALREADY_ASSIGNED |
| Reward数量 | 条件付きUPDATE＋version | 409 REWARD_SOLD_OUTまたは競合再試行1回 |
| Webhook | event_id PK | 重複は正常終了 |
| Outbox配送 | FOR UPDATE SKIP LOCKED | 別Workerが処理中ならスキップ |
| Batch起動 | job_lockまたはShedLock相当 | 多重起動はスキップ |


# 6. API詳細設計

## 6.1 共通HTTP仕様

| 項目 | 仕様 |
| --- | --- |
| Content-Type | application/json; charset=utf-8 |
| API Prefix | /api/v1 |
| 認証 | BFFセッションまたはBearer token。APIはSpring Securityで検証。 |
| Correlation ID | X-Correlation-Id。未指定時採番し応答にも返す。 |
| Idempotency | 支援・返金・重要作成系でIdempotency-Key必須。 |
| 日時 | ISO 8601 UTC |
| 金額 | 整数円 |
| 問題応答 | RFC 9457 Problem Details準拠 |
| 最大Body | 通常1MB。ファイル本体はS3へ直接送信。 |
| タイムアウト | BFF→API 10秒、外部連携は個別定義。 |

## 6.2 共通エラー対応

| HTTP | 分類 | 例 |
| --- | --- | --- |
| 400 | 形式・入力検証 | VALIDATION_ERROR |
| 401 | 未認証 | AUTHENTICATION_REQUIRED |
| 403 | 権限・所有権不足 | ACCESS_DENIED |
| 404 | 対象なし | PROJECT_NOT_FOUND |
| 409 | 状態・競合・冪等処理中 | PROJECT_INVALID_STATE / OPTIMISTIC_LOCK_CONFLICT |
| 422 | 業務条件不充足 | PROJECT_INCOMPLETE |
| 429 | レート制限 | RATE_LIMIT_EXCEEDED |
| 500 | 予期しない障害 | INTERNAL_ERROR |
| 503 | 外部依存・一時障害 | DEPENDENCY_UNAVAILABLE |

## 6.3 API-PJ-003 下書き作成

| 項目 | 内容 |
| --- | --- |
| Method / Path | POST `/api/v1/owner/projects` |
| 認可 | OWNER |
| 冪等性 | 任意。BFFで二重送信抑止。 |
| 処理 | CreateProjectUseCaseを呼び、ProjectをDRAFTで作成する。 |
| 正常応答 | 201 Created / Location: /owner/projects/{projectId} |
| 主なエラー | 400 VALIDATION_ERROR、403 ACCESS_DENIED |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| title | string | 必須 | 1～100文字 |
| summary | string | 必須 | 1～300文字 |
| body | string | 必須 | 1～20,000文字 |
| targetAmount | integer | 必須 | 1,000～100,000,000 |
| fundingType | enum | 必須 | ALL_OR_NOTHING / ALL_IN |
| startAt | datetime | 必須 | 現在より後 |
| endAt | datetime | 必須 | 開始後・180日以内 |
| mainFileId | string | 任意 | ULID |
| rewardPlans | array | 任意 | 0～100件 |

## 6.4 API-PJ-004 下書き更新

| 項目 | 内容 |
| --- | --- |
| Method / Path | PUT `/api/v1/owner/projects/{projectId}` |
| 認可 | OWNER＋所有者 |
| 冪等性 | expectedVersion必須。 |
| 処理 | Project取得、所有権・状態・version確認後、updateDraftする。削除されたRewardは参照有無を確認する。 |
| 正常応答 | 200 OK：projectId, status, version, updatedAt |
| 主なエラー | 404 PROJECT_NOT_FOUND、409 OPTIMISTIC_LOCK_CONFLICT / PROJECT_INVALID_STATE |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| expectedVersion | integer | 必須 | 0以上 |
| title | string | 必須 | 1～100文字 |
| summary | string | 必須 | 1～300文字 |
| body | string | 必須 | 1～20,000文字 |
| targetAmount | integer | 必須 | 範囲内 |
| fundingType | enum | 必須 | 許可値 |
| startAt/endAt | datetime | 必須 | 期間整合 |
| rewardPlans | array | 必須 | 0～100件 |

## 6.5 API-PJ-005 審査申請

| 項目 | 内容 |
| --- | --- |
| Method / Path | POST `/api/v1/owner/projects/{projectId}/review-requests` |
| 認可 | OWNER＋所有者 |
| 冪等性 | Idempotency-Key必須。 |
| 処理 | ProjectSubmissionPolicyで完全性を検証し、ProjectとReviewRequestを同一トランザクションで保存する。 |
| 正常応答 | 202 Accepted：reviewId, projectStatus, submittedAt |
| 主なエラー | 409 PROJECT_INVALID_STATE、422 PROJECT_INCOMPLETE |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| expectedVersion | integer | 必須 | 0以上 |
| confirmations | array | 必須 | 必要な確認コードを全て含む |

## 6.6 API-RV-004 審査承認

| 項目 | 内容 |
| --- | --- |
| Method / Path | POST `/api/v1/reviews/{reviewId}/approve` |
| 認可 | REVIEWER＋担当者 |
| 冪等性 | Idempotency-Key推奨。 |
| 処理 | Review.approveとProject.approveを同一トランザクションで実行し、通知Eventを保存する。 |
| 正常応答 | 200 OK：reviewStatus, projectStatus, approvedAt |
| 主なエラー | 409 REVIEW_INVALID_STATE、422 REVIEW_CHECKLIST_INCOMPLETE |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| expectedVersion | integer | 必須 | Review version |
| checklist | object | 必須 | 全必須項目true |
| comment | string | 任意 | 0～2,000文字 |

## 6.7 API-FD-001 支援申込

| 項目 | 内容 |
| --- | --- |
| Method / Path | POST `/api/v1/projects/{projectId}/supports` |
| 認可 | SUPPORTER |
| 冪等性 | Idempotency-Key必須。userId＋keyで一意。 |
| 処理 | 支援可否、リターン数量を検証し、Support/Payment/Outboxを作成する。 |
| 正常応答 | 202 Accepted：supportId, paymentStatus=PENDING, statusUrl |
| 主なエラー | 409 PROJECT_NOT_SUPPORTABLE / REWARD_SOLD_OUT / IDEMPOTENCY_IN_PROGRESS |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| rewardPlanId | string | 任意 | ULID |
| quantity | integer | 必須 | 1～99 |
| additionalAmount | integer | 任意 | 0以上 |
| contactEmail | string | 必須 | メール形式 |
| termsAccepted | boolean | 必須 | true |

## 6.8 API-PY-001 決済Webhook

| 項目 | 内容 |
| --- | --- |
| Method / Path | POST `/api/v1/payments/webhooks` |
| 認可 | Provider署名 |
| 冪等性 | 外部event_idを主キーとして重複排除。 |
| 処理 | 署名検証、受信履歴INSERT、Payment/Support状態更新、Outbox保存。 |
| 正常応答 | 204 No Content |
| 主なエラー | 401 PAYMENT_SIGNATURE_INVALID、500 WEBHOOK_PROCESSING_ERROR |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| rawBody | bytes | 必須 | 最大256KB |
| signature | header | 必須 | Provider形式 |
| eventTimestamp | header/payload | 必須 | 許容時間差5分 |

## 6.9 API-RF-001 返金要求

| 項目 | 内容 |
| --- | --- |
| Method / Path | POST `/api/v1/operations/supports/{supportId}/refunds` |
| 認可 | OPERATOR |
| 冪等性 | Idempotency-Key必須。 |
| 処理 | RefundEligibilityPolicy、既存返金確認後、RefundをREQUESTEDで作成しEvent保存。 |
| 正常応答 | 202 Accepted：refundId, status=REQUESTED |
| 主なエラー | 409 REFUND_ALREADY_EXISTS、422 REFUND_NOT_ALLOWED |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| reasonCode | enum | 必須 | PROJECT_FAILED / OPERATIONAL / USER_CANCEL |
| comment | string | 条件付き | 運用理由時必須、1～2,000文字 |
| amount | integer | 任意 | 省略時全額 |

## 6.10 API-FL-001 署名付きアップロードURL発行

| 項目 | 内容 |
| --- | --- |
| Method / Path | POST `/api/v1/files/presigned-uploads` |
| 認可 | 認証済 |
| 冪等性 | fileId採番。 |
| 処理 | メタデータ検証、FileObject=PENDING生成、S3 PutObject URL発行。 |
| 正常応答 | 201 Created：fileId, uploadUrl, headers, expiresAt |
| 主なエラー | 400 FILE_TYPE_NOT_ALLOWED / FILE_TOO_LARGE |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| purpose | enum | 必須 | PROJECT_MAIN / PROJECT_ATTACHMENT |
| fileName | string | 必須 | 1～255文字 |
| contentType | string | 必須 | image/jpeg/png/webp, application/pdf |
| size | integer | 必須 | 1～10MB |
| sha256 | string | 必須 | 64桁hex |

## 6.11 API-FL-002 アップロード完了

| 項目 | 内容 |
| --- | --- |
| Method / Path | POST `/api/v1/files/{fileId}/complete` |
| 認可 | 所有者 |
| 冪等性 | 同一ハッシュなら再実行可。 |
| 処理 | S3 HeadObjectで存在・size・typeを照合しCOMPLETEへ変更。 |
| 正常応答 | 200 OK：fileId, status, downloadReference |
| 主なエラー | 409 FILE_METADATA_MISMATCH、404 FILE_NOT_FOUND |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| sha256 | string | 必須 | 発行時と一致 |

## 6.12 API-AD-002 ロール更新

| 項目 | 内容 |
| --- | --- |
| Method / Path | PUT `/api/v1/admin/users/{userId}/roles` |
| 認可 | ADMIN |
| 冪等性 | expectedVersion必須。 |
| 処理 | 対象利用者、管理者保護ルールを検証し、ロールを差し替えて監査記録。 |
| 正常応答 | 200 OK：userId, roles, version |
| 主なエラー | 403 ROLE_UPDATE_FORBIDDEN、409 OPTIMISTIC_LOCK_CONFLICT |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| roles | array | 必須 | 1件以上、許可ロール |
| expectedVersion | integer | 必須 | 0以上 |
| reason | string | 必須 | 1～500文字 |

## 6.13 API-AU-001 監査ログ検索

| 項目 | 内容 |
| --- | --- |
| Method / Path | GET `/api/v1/audit-logs` |
| 認可 | ADMIN/AUDITOR |
| 冪等性 | 不要。 |
| 処理 | MyBatis Read Modelで検索し、機微情報をマスクして返す。 |
| 正常応答 | 200 OK：PageResult<AuditLogResponse> |
| 主なエラー | 400 DATE_RANGE_TOO_LARGE |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| from/to | datetime | 必須 | 最大31日 |
| actorUserId | string | 任意 | ULID |
| action | string | 任意 | 前方一致不可 |
| resourceType/id | string | 任意 | 完全一致 |
| page/size | integer | 任意 | size最大100 |

## 6.14 API-US-002 プロフィール更新

| 項目 | 内容 |
| --- | --- |
| Method / Path | PUT `/api/v1/me` |
| 認可 | 認証済 |
| 冪等性 | expectedVersion必須。 |
| 処理 | 入力正規化、重複確認、プロフィール更新、監査記録。 |
| 正常応答 | 200 OK：profile, version |
| 主なエラー | 409 EMAIL_ALREADY_USED / OPTIMISTIC_LOCK_CONFLICT |

| 項目 | 型 | 必須 | 検証 |
| --- | --- | --- | --- |
| displayName | string | 必須 | 1～100文字 |
| email | string | 必須 | メール形式 |
| expectedVersion | integer | 必須 | 0以上 |

## 6.15 OpenAPI・DTO生成規約

- OpenAPIファイルを `/docs/api/openapi.yaml` に配置し、Backend DTOとFrontend client型を生成する。
- 生成コードを直接編集しない。差分はOpenAPIへ戻す。
- 破壊的変更はCIで検出する。
- ドメイン型とAPI型の変換はMapperへ閉じ込める。
- APIレスポンスに内部versionを返す更新対象は、次回更新時のexpectedVersionに使用する。


# 7. 画面詳細設計

## 7.1 共通UI構造

```text
RootLayout
├─ Header
│  ├─ Logo / Navigation
│  ├─ UserMenu
│  └─ EnvironmentBadge（Production相当以外）
├─ Breadcrumb
├─ FlashMessageRegion（aria-live）
├─ Main
└─ Footer
```

## 7.2 共通コンポーネント

| Component | 責務 | 主なProps |
| --- | --- | --- |
| FormField | ラベル、入力、説明、エラー関連付け | name,label,required,error |
| MoneyInput | 円整数入力・桁区切り | value,min,max |
| DateTimeInput | 日時入力、JST表示/UTC送信 | value,min,max |
| StatusBadge | 状態コードの表示 | status |
| ConfirmDialog | 重要操作の再確認 | title,message,onConfirm |
| ErrorSummary | フォームエラー一覧 | errors |
| DataTable | ソート・ページング・空表示 | columns,rows,page |
| CorrelationError | 相関ID付きシステムエラー | correlationId |

## 7.3 SCR-021 プロジェクト編集

| 項目 | 内容 |
| --- | --- |
| 利用者 | OWNER |
| Route | `/owner/projects/[projectId]/edit` |
| 初期データ | Server ComponentからBackend APIを呼び出す。 |
| 画面制御 | versionをhidden保持。409時に自動上書きせず、最新データ再取得と差分確認を促す。 |

| 項目 | UI | 検証 | タイミング |
| --- | --- | --- | --- |
| タイトル | text | 必須、100文字 | 即時＋submit時 |
| 概要 | textarea | 必須、300文字 | submit時 |
| 本文 | markdown editor | 必須、20,000文字 | プレビュー時サニタイズ |
| 目標金額 | MoneyInput | 1,000～100,000,000 | blur/submit |
| 募集方式 | radio | 2択 | submit |
| 開始/終了 | DateTimeInput | 未来、180日以内 | 相互検証 |
| メイン画像 | FileUpload | 10MB | S3直接 |
| リターン | RewardEditor[] | 1～100件 | 配列検証 |

- 下書き保存→API-PJ-004
- プレビュー→SCR-022
- 審査申請→SCR-023
- 取消→一覧

## 7.4 SCR-031 審査詳細

| 項目 | 内容 |
| --- | --- |
| 利用者 | REVIEWER |
| Route | `/reviews/[reviewId]` |
| 初期データ | Server ComponentからBackend APIを呼び出す。 |
| 画面制御 | 審査開始後のみ判断ボタンを有効化。担当者以外はread only。 |

| 項目 | UI | 検証 | タイミング |
| --- | --- | --- | --- |
| 基本情報 | read only | Project全項目 | 取得時 |
| 添付 | file list | ウイルス確認済みのみ | 取得時 |
| チェックリスト | checkbox | 必須項目全てtrue | 承認時 |
| コメント | textarea | 差戻し・却下時必須 | 操作時 |
| 理由区分 | select | 却下時必須 | 操作時 |

- 審査開始→API-RV-003
- 承認→API-RV-004
- 差戻し→API-RV-005
- 却下→API-RV-006

## 7.5 SCR-040 支援入力

| 項目 | 内容 |
| --- | --- |
| 利用者 | SUPPORTER |
| Route | `/projects/[projectId]/support` |
| 初期データ | Server ComponentからBackend APIを呼び出す。 |
| 画面制御 | 支援確定時にIdempotency-Keyを生成し、結果確定まで同一キーを維持する。 |

| 項目 | UI | 検証 | タイミング |
| --- | --- | --- | --- |
| リターン | radio/card | 公開中・残数あり | 取得時 |
| 数量 | number | 1～99、残数以下 | 入力時 |
| 追加支援額 | MoneyInput | 0以上 | 入力時 |
| 連絡先 | email | 必須 | 入力時 |
| 規約同意 | checkbox | true必須 | submit |

- 確認→SCR-041
- 戻る→SCR-011

## 7.6 SCR-041 支援確認

| 項目 | 内容 |
| --- | --- |
| 利用者 | SUPPORTER |
| Route | `/projects/[projectId]/support/confirm` |
| 初期データ | Server ComponentからBackend APIを呼び出す。 |
| 画面制御 | 二重クリックを抑止するが、API冪等性を最終防御とする。 |

| 項目 | UI | 検証 | タイミング |
| --- | --- | --- | --- |
| 支援内容 | read only | session内draft | 表示時 |
| 合計額 | read only | サーバー再計算 | 表示時 |

- 支援確定→API-FD-001
- 修正→SCR-040

## 7.7 SCR-061 返金管理

| 項目 | 内容 |
| --- | --- |
| 利用者 | OPERATOR |
| Route | `/operations/refunds` |
| 初期データ | Server ComponentからBackend APIを呼び出す。 |
| 画面制御 | 全操作を監査記録し、金額と対象支援を確認ダイアログへ表示する。 |

| 項目 | UI | 検証 | タイミング |
| --- | --- | --- | --- |
| 検索条件 | filters | 期間最大31日 | 検索時 |
| 返金理由 | select | 必須 | 要求時 |
| コメント | textarea | 条件付き | 要求時 |

- 検索
- 返金要求→API-RF-001
- 再実行→API-RF-002

## 7.8 SCR-071 監査ログ検索

| 項目 | 内容 |
| --- | --- |
| 利用者 | ADMIN/AUDITOR |
| Route | `/admin/audit-logs` |
| 初期データ | Server ComponentからBackend APIを呼び出す。 |
| 画面制御 | 機微情報はマスク済みデータだけを表示する。 |

| 項目 | UI | 検証 | タイミング |
| --- | --- | --- | --- |
| 期間 | date range | 必須・最大31日 | 検索時 |
| 利用者 | user selector | 任意 | 検索時 |
| 操作 | select | 任意 | 検索時 |
| リソース | text | 任意 | 検索時 |

- 検索→API-AU-001
- 詳細表示
- CSV出力（権限付き）

## 7.9 フロントエンド状態管理

| 状態 | 保持先 | 方針 |
| --- | --- | --- |
| 認証セッション | HttpOnly Cookie/BFF | ブラウザJSからトークン参照不可 |
| Server State | Server Component / TanStack Query | 更新後invalidate |
| フォーム入力 | React Hook Form | 画面離脱時は原則破棄、下書きはAPI保存 |
| 一時支援内容 | 暗号化・短期sessionまたは再入力 | 個人情報をlocalStorageへ保存しない |
| UI状態 | Component state | モーダル、タブ、開閉のみ |

## 7.10 アクセシビリティ

- 入力には可視labelを関連付ける。
- エラーは対象入力のaria-describedbyへ関連付け、先頭エラーへフォーカスする。
- 色だけで状態を表現しない。
- モーダルはフォーカストラップとEscape閉じを提供する。
- 処理中はaria-busyを設定し、完了結果をaria-liveで通知する。
- 主要操作はキーボードのみで実行可能とする。


# 8. データベース詳細設計

## 8.1 共通列・物理方針

| 項目 | 仕様 |
| --- | --- |
| 文字コード | UTF-8 |
| Timezone | DB/接続ともUTC、表示時JST |
| 主キー | varchar(26) ULID |
| 監査列 | created_at, created_by, updated_at, updated_by |
| 楽観ロック | version bigint not null default 0 |
| 削除 | 原則論理状態。必要なマスタだけdeleted_at |
| DDL | Flyway Vxxxxx__description.sql |
| JSON | イベントPayload等の限定用途。主要検索項目をJSONへ隠さない。 |

## 8.2 app_user

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| user_id | varchar(26) | NO | PK | 内部利用者ID |
| cognito_subject | varchar(100) | NO | UQ | Cognito sub |
| email | varchar(320) | NO | UQ | 正規化メール |
| display_name | varchar(100) | NO |  | 表示名 |
| status | varchar(30) | NO |  | ACTIVE/SUSPENDED/WITHDRAWN |
| version | bigint | NO | default 0 | 楽観ロック |
| created_at | timestamptz | NO |  | 作成日時 |
| updated_at | timestamptz | NO |  | 更新日時 |

主要索引：UQ(cognito_subject), UQ(lower(email)), IDX(status)

## 8.3 role

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| role_code | varchar(30) | NO | PK | ロールコード |
| display_name | varchar(100) | NO |  | 表示名 |
| assignable | boolean | NO | default true | 管理画面付与可否 |

主要索引：FK列に必要な索引を作成する。

## 8.4 user_role

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| user_id | varchar(26) | NO | PK/FK | 利用者 |
| role_code | varchar(30) | NO | PK/FK | ロール |
| assigned_at | timestamptz | NO |  | 付与日時 |
| assigned_by | varchar(26) | NO |  | 付与者 |

主要索引：FK列に必要な索引を作成する。

## 8.5 project

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| project_id | varchar(26) | NO | PK | ProjectId |
| owner_user_id | varchar(26) | NO | FK | 起案者 |
| title | varchar(100) | NO |  | タイトル |
| summary | varchar(300) | NO |  | 概要 |
| body | text | NO |  | 本文 |
| target_amount | bigint | NO | check >=1000 | 目標金額 |
| funding_type | varchar(30) | NO |  | 募集方式 |
| start_at | timestamptz | NO |  | 開始 |
| end_at | timestamptz | NO | check end>start | 終了 |
| status | varchar(30) | NO |  | 状態 |
| main_file_id | varchar(26) | YES | FK | メイン画像 |
| version | bigint | NO | default 0 | 楽観ロック |
| created_at | timestamptz | NO |  | 作成 |
| updated_at | timestamptz | NO |  | 更新 |

主要索引：IDX(status,start_at,end_at), IDX(owner_user_id,updated_at desc)

## 8.6 reward_plan

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| reward_plan_id | varchar(26) | NO | PK | ID |
| project_id | varchar(26) | NO | FK | Project |
| name | varchar(100) | NO |  | 名称 |
| description | varchar(2000) | NO |  | 説明 |
| unit_amount | bigint | NO | check >0 | 最低金額 |
| quantity_limit | integer | YES | check >0 | 数量上限 |
| reserved_quantity | integer | NO | default 0 | 予約数 |
| display_order | integer | NO |  | 表示順 |
| version | bigint | NO | default 0 | 楽観ロック |

主要索引：IDX(project_id,display_order)

## 8.7 project_status_history

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| history_id | varchar(26) | NO | PK | ID |
| project_id | varchar(26) | NO | FK | Project |
| from_status | varchar(30) | YES |  | 作成時null |
| to_status | varchar(30) | NO |  | 遷移後 |
| reason | varchar(2000) | YES |  | 理由 |
| changed_at | timestamptz | NO |  | 遷移日時 |
| changed_by | varchar(26) | YES |  | システム時null |

主要索引：FK列に必要な索引を作成する。

## 8.8 review_request

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| review_id | varchar(26) | NO | PK | ReviewId |
| project_id | varchar(26) | NO | FK/UQ(active) | Project |
| status | varchar(30) | NO |  | REQUESTED等 |
| reviewer_user_id | varchar(26) | YES | FK | 担当者 |
| submitted_at | timestamptz | NO |  | 申請日時 |
| started_at | timestamptz | YES |  | 開始日時 |
| completed_at | timestamptz | YES |  | 完了日時 |
| version | bigint | NO | default 0 | 楽観ロック |

主要索引：IDX(status,submitted_at), 部分一意(project_id where status in REQUESTED,UNDER_REVIEW)

## 8.9 review_history

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| review_history_id | varchar(26) | NO | PK | ID |
| review_id | varchar(26) | NO | FK | Review |
| action | varchar(30) | NO |  | START/APPROVE/RETURN/REJECT |
| reason_code | varchar(50) | YES |  | 理由区分 |
| comment | varchar(2000) | YES |  | コメント |
| checklist_json | jsonb | YES |  | 承認チェック |
| acted_at | timestamptz | NO |  | 操作日時 |
| acted_by | varchar(26) | NO |  | 審査者 |

主要索引：FK列に必要な索引を作成する。

## 8.10 support

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| support_id | varchar(26) | NO | PK | SupportId |
| project_id | varchar(26) | NO | FK | Project |
| supporter_user_id | varchar(26) | NO | FK | 支援者 |
| support_amount | bigint | NO | check >0 | 合計額 |
| status | varchar(30) | NO |  | 支援状態 |
| idempotency_key | varchar(100) | NO |  | 冪等キー |
| payment_id | varchar(26) | YES | FK/UQ | Payment |
| contact_email | varchar(320) | NO |  | 通知先 |
| version | bigint | NO | default 0 | 楽観ロック |
| created_at | timestamptz | NO |  | 作成 |
| updated_at | timestamptz | NO |  | 更新 |

主要索引：UQ(supporter_user_id,idempotency_key), IDX(project_id,status), IDX(supporter_user_id,created_at desc)

## 8.11 support_item

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| support_item_id | varchar(26) | NO | PK | ID |
| support_id | varchar(26) | NO | FK | Support |
| reward_plan_id | varchar(26) | YES | FK | Reward |
| quantity | integer | NO | check >0 | 数量 |
| unit_amount | bigint | NO | check >0 | 申込時単価 |
| amount | bigint | NO | check >0 | 小計 |

主要索引：FK列に必要な索引を作成する。

## 8.12 payment

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| payment_id | varchar(26) | NO | PK | PaymentId |
| support_id | varchar(26) | NO | FK/UQ | Support |
| provider | varchar(30) | NO |  | 決済事業者 |
| provider_payment_id | varchar(100) | YES | UQ | 外部ID |
| amount | bigint | NO | check >0 | 金額 |
| status | varchar(30) | NO |  | 状態 |
| failure_code | varchar(100) | YES |  | 失敗コード |
| processed_at | timestamptz | YES |  | 確定日時 |
| version | bigint | NO | default 0 | 楽観ロック |

主要索引：UQ(provider,provider_payment_id), IDX(status,updated_at)

## 8.13 payment_webhook_event

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| webhook_event_id | varchar(100) | NO | PK | 外部event ID |
| provider | varchar(30) | NO |  | 事業者 |
| event_type | varchar(100) | NO |  | 種別 |
| payload_hash | varchar(64) | NO |  | SHA-256 |
| payload | jsonb | NO |  | 必要最小Payload |
| received_at | timestamptz | NO |  | 受信 |
| processed_at | timestamptz | YES |  | 処理完了 |
| process_status | varchar(30) | NO |  | RECEIVED/PROCESSED/ERROR |
| retry_count | integer | NO | default 0 | 再試行 |
| last_error_code | varchar(100) | YES |  | 最終エラー |

主要索引：FK列に必要な索引を作成する。

## 8.14 refund

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| refund_id | varchar(26) | NO | PK | RefundId |
| payment_id | varchar(26) | NO | FK | Payment |
| support_id | varchar(26) | NO | FK | Support |
| amount | bigint | NO | check >0 | 返金額 |
| reason_code | varchar(50) | NO |  | 理由 |
| comment | varchar(2000) | YES |  | 運用コメント |
| status | varchar(30) | NO |  | 状態 |
| provider_refund_id | varchar(100) | YES | UQ | 外部ID |
| retry_count | integer | NO | default 0 | 再試行 |
| next_retry_at | timestamptz | YES |  | 次回 |
| version | bigint | NO | default 0 | 楽観ロック |

主要索引：IDX(status,next_retry_at), IDX(support_id)

## 8.15 file_object

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| file_id | varchar(26) | NO | PK | FileId |
| owner_user_id | varchar(26) | NO | FK | 所有者 |
| purpose | varchar(50) | NO |  | 用途 |
| s3_bucket | varchar(63) | NO |  | Bucket |
| s3_key | varchar(1024) | NO | UQ | Object Key |
| original_name | varchar(255) | NO |  | 元ファイル名 |
| content_type | varchar(100) | NO |  | MIME |
| size_bytes | bigint | NO |  | サイズ |
| sha256 | varchar(64) | NO |  | ハッシュ |
| status | varchar(30) | NO |  | PENDING/COMPLETE/DELETED |
| expires_at | timestamptz | YES |  | 未完了失効 |

主要索引：FK列に必要な索引を作成する。

## 8.16 notification

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| notification_id | varchar(26) | NO | PK | ID |
| business_key | varchar(200) | NO |  | 重複防止キー |
| channel | varchar(30) | NO |  | EMAIL/IN_APP |
| template_id | varchar(100) | NO |  | テンプレート |
| recipient_user_id | varchar(26) | YES | FK | 受信者 |
| recipient_address | varchar(320) | YES |  | 送信先 |
| variables | jsonb | NO |  | テンプレート変数 |
| status | varchar(30) | NO |  | 状態 |
| scheduled_at | timestamptz | NO |  | 送信予定 |
| retry_count | integer | NO | default 0 | 再試行 |

主要索引：UQ(business_key,channel), IDX(status,scheduled_at)

## 8.17 notification_delivery

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| delivery_id | varchar(26) | NO | PK | ID |
| notification_id | varchar(26) | NO | FK | Notification |
| attempt_no | integer | NO |  | 試行番号 |
| provider_message_id | varchar(200) | YES |  | 外部ID |
| result | varchar(30) | NO |  | SUCCESS/FAILURE |
| error_code | varchar(100) | YES |  | エラー |
| attempted_at | timestamptz | NO |  | 試行日時 |

主要索引：FK列に必要な索引を作成する。

## 8.18 outbox_event

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| event_id | varchar(26) | NO | PK | EventId |
| aggregate_type | varchar(100) | NO |  | 集約型 |
| aggregate_id | varchar(26) | NO |  | 集約ID |
| event_type | varchar(200) | NO |  | イベント型 |
| payload | jsonb | NO |  | Payload |
| occurred_at | timestamptz | NO |  | 発生 |
| publish_status | varchar(30) | NO |  | PENDING/PUBLISHED/ERROR |
| retry_count | integer | NO | default 0 | 再試行 |
| next_retry_at | timestamptz | YES |  | 次回 |
| published_at | timestamptz | YES |  | 完了 |

主要索引：IDX(publish_status,next_retry_at,occurred_at)

## 8.19 audit_log

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| audit_id | varchar(26) | NO | PK | ID |
| occurred_at | timestamptz | NO |  | 発生 |
| actor_user_id | varchar(26) | YES |  | 実行者 |
| action | varchar(100) | NO |  | 操作 |
| resource_type | varchar(100) | NO |  | 対象種別 |
| resource_id | varchar(100) | YES |  | 対象ID |
| result | varchar(30) | NO |  | SUCCESS/FAILURE |
| correlation_id | varchar(64) | NO |  | 相関ID |
| detail | jsonb | NO |  | マスク済み詳細 |
| client_ip_hash | varchar(64) | YES |  | IPハッシュ |

主要索引：IDX(occurred_at desc), IDX(actor_user_id,occurred_at desc), IDX(resource_type,resource_id)

## 8.20 ai_activity_log

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| ai_activity_id | varchar(26) | NO | PK | ID |
| occurred_at | timestamptz | NO |  | 実行 |
| actor_user_id | varchar(26) | NO |  | 操作者 |
| tool_name | varchar(100) | NO |  | Codex/Copilot等 |
| task_id | varchar(100) | YES |  | Issue/PR |
| action_type | varchar(50) | NO |  | GENERATE/REVIEW等 |
| repository | varchar(200) | NO |  | 対象Repo |
| changed_paths | jsonb | NO |  | 変更パス |
| prompt_hash | varchar(64) | YES |  | 本文ではなくハッシュ |
| result | varchar(30) | NO |  | ACCEPTED/REJECTED/MODIFIED |
| approved_by | varchar(26) | YES |  | 人間承認者 |

主要索引：IDX(occurred_at desc), IDX(actor_user_id,occurred_at desc)

## 8.21 idempotency_record

| 列名 | 型 | Null | 制約 | 説明 |
| --- | --- | --- | --- | --- |
| scope | varchar(100) | NO | PK | API/業務範囲 |
| actor_id | varchar(100) | NO | PK | 利用者等 |
| idempotency_key | varchar(100) | NO | PK | キー |
| request_hash | varchar(64) | NO |  | 要求ハッシュ |
| status | varchar(30) | NO |  | PROCESSING/COMPLETED/FAILED |
| response_status | integer | YES |  | HTTP status |
| response_body | jsonb | YES |  | 再応答用 |
| expires_at | timestamptz | NO |  | 有効期限 |
| created_at | timestamptz | NO |  | 作成 |

主要索引：IDX(expires_at)

## 8.22 DB制約とアプリ検証の分担

| 制約 | DB | Application |
| --- | --- | --- |
| 必須・長さ | NOT NULL / varchar長 | 入力メッセージと業務文脈 |
| 金額・数量 | CHECK | Value Object検証 |
| 一意性 | UNIQUE | 事前確認＋競合時例外変換 |
| 参照整合性 | FK | 対象存在・権限確認 |
| 状態遷移 | 原則Application | Domain Method |
| 冪等性 | PK/UNIQUE | 要求ハッシュ照合 |
| 楽観ロック | version条件 | @Versionまたは明示UPDATE |

## 8.23 Flyway規約

```text
V202607200001__create_identity_tables.sql
V202607200002__create_project_tables.sql
V202607200003__create_review_tables.sql
V202607200004__create_funding_payment_tables.sql
V202607200005__create_outbox_audit_tables.sql
R__views_for_read_model.sql
```

- 適用済みVersioned migrationを変更しない。修正は新規Migrationで行う。
- 大量更新は単一DDLに含めず、事前検証・段階移行・ロールバック方針を記載する。
- DDLとEntity/Mapper/OpenAPIの差分をPRで同時にレビューする。


# 9. バッチ・非同期処理詳細設計

| Batch ID | 名称 | 周期 | 抽出 | 処理 | 排他・再試行 |
| --- | --- | --- | --- | --- | --- |
> **バッチIDは基本設計 §8.1 を正とする。** 初版の本表は独自採番で、Outbox配送をBAT-003、
> 通知送信をBAT-004、返金実行をBAT-005…としていたが、実装は基本設計の採番を用いている
> （`@SchedulerLock(name = "BAT-00X-…")`）。本表を実装に合わせて振り直した。

| Batch ID | 名称 | 周期 | 抽出 | 処理 | 排他・再試行 |
| --- | --- | --- | --- | --- | --- |
| BAT-001 | 公開開始 | 毎分 | APPROVEDかつstart_at<=now | PUBLISHEDへ遷移、ProjectPublished Event | 100件/Tx、SKIP LOCKED、ShedLock |
| BAT-002 | 募集終了 | 毎分 | PUBLISHEDかつend_at<=now | 成立判定、SUCCEEDED/FAILED、返金Event | 100件/Tx、ShedLock |
| BAT-003 | 返金対象作成 | イベント | `ProjectFailed` の購読 | 不成立案件の返金要求を作成 | スケジュール起動ではない（`ProjectFailedHandler`） |
| BAT-004 | 返金実行 | 毎分 | REQUESTED/RETRY_WAIT | Provider返金API | 最大8回、上限後OPERATOR通知、ShedLock |
| BAT-005 | 通知送信 | 毎分 | PENDING/RETRY_WAIT | SES送信、delivery記録 | 最大5回、ShedLock |
| BAT-006 | Outbox配送 | 5秒 | PENDINGかつnext_retry_at<=now | **アプリ内Handlerへ配送**（ADR-0008） | 50件/Tx、指数Backoff、SKIP LOCKED。**競合コンシューマ設計のためShedLock対象外**（ADR-0003） |
| BAT-007 | 決済照合 | 15分 | Payment=UNKNOWN | Provider照会、状態確定 | 24時間後手動対応、ShedLock |
| BAT-008 | 未完了ファイル削除 | 日次 | PENDINGかつexpires_at<now | S3削除、DELETED | 1000件/回、ShedLock |
| BAT-009 | 監査ログアーカイブ | 月次 | 保持期限超過 | 専用S3バケットへ `GLACIER_IR` で出力後、件数・hash検証のうえ削除（ADR-0009） | 件数・hash検証、ShedLock |
| BAT-010 | 冪等記録削除 | 日次 | expires_at<now | 物理削除 | 10,000件/Tx、ShedLock |

## 9.1 Outbox Workerアルゴリズム

```sql
BEGIN;
SELECT * FROM outbox_event
 WHERE publish_status IN ('PENDING','ERROR')
   AND COALESCE(next_retry_at, occurred_at) <= now()
 ORDER BY occurred_at
 FOR UPDATE SKIP LOCKED
 LIMIT 50;

各Eventを配送
  成功: PUBLISHED, published_at更新
  失敗: retry_count増分、next_retry_at設定
  上限超過: ERROR固定、運用通知
COMMIT;
```

## 9.2 再試行方針

| 対象 | 再試行 | 間隔 | 非再試行 |
| --- | --- | --- | --- |
| SES | 最大5回 | 1m,5m,15m,1h,6h | 4xx形式不正 |
| 決済照会 | 最大8回 | 指数＋jitter | 署名不正、契約エラー |
| 返金 | 最大8回 | 1m～24h | 返金不可、金額不整合 |
| DB deadlock | 最大2回 | 100ms～500ms jitter | 制約違反 |
| S3 HeadObject | 最大3回 | 1s,3s,10s | 404失効後 |

## 9.3 バッチ監視

| メトリクス | 警告 | 重大 |
| --- | --- | --- |
| outbox_pending_count | >1000 10分 | >5000 5分 |
| oldest_outbox_age | >5分 | >30分 |
| notification_failure_rate | >5%/15分 | >20%/10分 |
| refund_retry_count | >10件 | >50件 |
| batch_last_success_age | 周期×2 | 周期×5 |

# 10. 外部インターフェース詳細設計

## 10.1 Amazon Cognito

| 項目 | 仕様 |
| --- | --- |
| Flow | Authorization Code＋PKCE |
| Token保持 | BFF側の暗号化セッション。ブラウザlocalStorage禁止。 |
| Claim mapping | sub→cognito_subject、email、email_verified |
| Session | Idle 30分、Absolute 8時間 |
| Logout | Cognito logout＋BFF session破棄 |
| 障害時 | 新規ログイン不可。既存sessionは期限内利用可とするか運用判断。 |

## 10.2 S3アップロード

```text
Client -> API: presigned URL要求
API -> DB: FileObject(PENDING)作成
API -> S3: PutObject URL署名
API --> Client: URL, required headers
Client -> S3: PUT file
Client -> API: complete(fileId, sha256)
API -> S3: HeadObject
API -> DB: FileObject(COMPLETE)
```

| 制御 | 仕様 |
| --- | --- |
| Key | env/userId/fileId/randomized-name |
| 暗号化 | SSE-S3またはSSE-KMS |
| 公開 | Block Public Access有効 |
| URL期限 | 5分 |
| Content-Type | 署名条件に含める |
| サイズ | 発行時と完了時に検証 |
| ウイルス検査 | 教育環境はモック可。本番想定ではEvent→Scanner→CLEAN状態を追加。 |

## 10.3 Amazon SES

| 項目 | 仕様 |
| --- | --- |
| 送信元 | 環境別に検証済みDomain |
| テンプレート | コード管理、template_idで指定 |
| 個人情報 | 変数に必要最小限。ログは宛先ハッシュ。 |
| Bounce/Complaint | SES構成セットのイベント送信先で受信し送信抑止へ反映（**未実装**。構成セットのみ作成済み） |
| Timeout | 5秒 |
| Retry | Notification Worker方針に従う |

## 10.4 決済Sandbox Adapter

| Port Method | 入力 | 出力 | Timeout |
| --- | --- | --- | --- |
| createPayment | paymentId, amount, returnUrl | providerPaymentId, status, action | 10秒 |
| getPayment | providerPaymentId | status, amount, updatedAt | 5秒 |
| requestRefund | providerPaymentId, amount, key | providerRefundId, status | 10秒 |
| getRefund | providerRefundId | status | 5秒 |
| verifyWebhook | headers, rawBody | verified event | 1秒 |

Provider SDK型をドメイン・Applicationへ返さず、Adapter内部で標準型へ変換する。Providerのエラーコードは内部 `PaymentDependencyException` と `ProviderDeclineReason` へマッピングする。

## 10.5 外部障害時の扱い

| 障害 | ユーザー応答 | 内部処理 |
| --- | --- | --- |
| 決済開始Timeout | 202・確認中 | Payment=UNKNOWN、照合Batch |
| SES障害 | 業務処理は成功 | Notification=RETRY_WAIT |
| S3障害 | 503 | FileObjectをPENDINGのまま失効 |
| Cognito障害 | 503/ログイン不可 | 既存Session方針に従う |
| 配送Handler障害 | 業務Commitは成功 | OutboxをPENDINGで保持し指数Backoffで再試行 |


# 11. セキュリティ詳細設計

## 11.1 認証・認可チェーン

```text
Browser
  -> Next.js Middleware: session存在確認
  -> BFF Route Handler: CSRF / session / token refresh
  -> Spring Security: JWT issuer, audience, expiry検証
  -> Method Security: role検証
  -> UseCase: ownership / resource state検証
```

## 11.2 Spring Security設定

- 公開APIは `/api/v1/projects/**` のGET、決済Webhook、health endpointの必要部分に限定する。
- `@PreAuthorize`は粗いロール制御に使用し、所有権はUseCaseで判定する。
- CORSはBFFと管理されたOriginだけを許可する。
- 管理APIはADMIN/AUDITOR等の明示ロールを要求する。
- エラーで認証・認可内部理由を過度に公開しない。

## 11.3 Webセキュリティ

| 脅威 | 対策 |
| --- | --- |
| CSRF | SameSite Cookie＋CSRF Token、状態変更はPOST/PUT等 |
| XSS | React escape、Rich Text sanitize、CSP |
| SQL Injection | JPA parameter/MyBatis bind、文字列連結禁止 |
| SSRF | 外部URL入力を原則禁止、許可先固定 |
| File attack | MIME/size/hash検証、非公開S3、Content-Disposition |
| Brute force | Cognito制御＋API rate limit |
| Privilege escalation | Backend認可、管理者自己保護、監査 |
| Replay | Webhook timestamp＋event ID、Idempotency-Key |

## 11.4 秘密情報

| 情報 | 保存先 | 禁止 |
| --- | --- | --- |
| DB password | Secrets Manager | Git、envファイル配布 |
| Provider secret | Secrets Manager | ログ、AIプロンプト |
| Cognito client secret | BFF runtime secret | ブラウザ配布 |
| Terraform state | 暗号化S3＋lock | ローカル共有 |
| AI token | 開発者/CI Secret | リポジトリ、ログ |

## 11.5 監査対象操作

| 操作 | Audit action |
| --- | --- |
| 審査申請 | PROJECT_SUBMIT_REVIEW |
| 審査判断 | REVIEW_APPROVE/RETURN/REJECT |
| 支援申込・取消 | SUPPORT_CREATE/CANCEL |
| 返金 | REFUND_REQUEST/RETRY |
| ロール更新・会員停止 | USER_ROLE_UPDATE/USER_SUSPEND |
| 監査ログ出力 | AUDIT_EXPORT |
| AI生成採用 | AI_CHANGE_ACCEPT |
| 本番相当デプロイ | DEPLOY_APPROVE |

# 12. 例外・ログ・監視詳細設計

## 12.1 例外階層

```text
ApplicationException
├─ ValidationException                 -> 400
├─ AuthenticationRequiredException     -> 401
├─ AccessDeniedException               -> 403
├─ ResourceNotFoundException           -> 404
├─ ConflictException
│  ├─ InvalidStateException            -> 409
│  ├─ OptimisticLockConflictException  -> 409
│  └─ IdempotencyConflictException     -> 409
├─ BusinessRuleViolationException      -> 422
└─ DependencyException                 -> 503

予期しないRuntimeException            -> 500
```

## 12.2 エラーコード

| Code | HTTP | 意味 |
| --- | --- | --- |
| PROJECT_NOT_FOUND | 404 | 対象プロジェクトが存在しない |
| PROJECT_INVALID_STATE | 409 | 現在状態で操作不可 |
| PROJECT_INCOMPLETE | 422 | 審査申請条件不足 |
| OPTIMISTIC_LOCK_CONFLICT | 409 | 同時更新 |
| REVIEW_ALREADY_ASSIGNED | 409 | 別審査者が開始済み |
| REVIEW_CHECKLIST_INCOMPLETE | 422 | 承認項目不足 |
| PROJECT_NOT_SUPPORTABLE | 409 | 支援受付不可 |
| REWARD_SOLD_OUT | 409 | 数量不足 |
| IDEMPOTENCY_IN_PROGRESS | 409 | 同一要求処理中 |
| PAYMENT_SIGNATURE_INVALID | 401 | Webhook署名不正 |
| PAYMENT_EVENT_CONFLICT | 409 | 状態とEvent不整合 |
| REFUND_NOT_ALLOWED | 422 | 返金不可 |
| FILE_TYPE_NOT_ALLOWED | 400 | MIME不許可 |
| FILE_METADATA_MISMATCH | 409 | S3情報不一致 |
| ROLE_UPDATE_FORBIDDEN | 403 | 保護ルール違反 |
| DEPENDENCY_UNAVAILABLE | 503 | 外部依存一時障害 |

## 12.3 構造化ログ

```json
{
  "timestamp": "2026-07-20T12:34:56.123Z",
  "level": "INFO",
  "service": "cf-api",
  "environment": "dev",
  "correlationId": "cor_...",
  "traceId": "...",
  "userId": "usr_...",
  "action": "PROJECT_SUBMIT_REVIEW",
  "resourceId": "prj_...",
  "result": "SUCCESS",
  "durationMs": 42
}
```

## 12.4 ログレベル

| Level | 用途 |
| --- | --- |
| ERROR | 処理失敗、運用対応が必要。スタックトレースは内部ログのみ。 |
| WARN | 再試行、競合、不正要求、外部一時障害。 |
| INFO | 重要業務操作、起動・停止、Batch結果。 |
| DEBUG | 開発環境の詳細。個人情報・Token禁止。 |
| TRACE | 通常無効。短時間の限定調査のみ。 |

## 12.5 メトリクス

| Metric | Type | Labels | 目的 |
| --- | --- | --- | --- |
| http_server_duration | histogram | method,route,status | API性能 |
| business_operation_total | counter | operation,result | 業務成功率 |
| payment_status_total | gauge/counter | status,provider | 決済滞留 |
| outbox_pending | gauge | eventType | 非同期滞留 |
| batch_duration | histogram | batchId,result | Batch監視 |
| db_pool_active | gauge | pool | 接続枯渇 |
| jvm_gc_pause | histogram | action | JVM健全性 |
| ai_change_total | counter | tool,result | AI利用状況 |

## 12.6 アラート

| Alert | 条件 | 初動 |
| --- | --- | --- |
| API 5xx増加 | 5分で5%以上 | 直近Deploy、例外、依存障害を確認 |
| p95遅延 | 500ms超15分 | DB slow query、外部呼出し、CPU確認 |
| Outbox滞留 | oldest>30分 | Worker、配送Handler、Eventエラー確認 |
| Payment UNKNOWN | 10件超/10分 | Provider障害、照合Batch確認 |
| DB接続逼迫 | active>80% 10分 | 長時間Tx、Pool、負荷確認 |
| 認証失敗急増 | 通常の3倍 | Cognito、攻撃、設定変更確認 |


# 13. 構成・デプロイ詳細設計

## 13.1 環境変数

| 変数 | 例 | 秘密 | 説明 |
| --- | --- | --- | --- |
| SPRING_PROFILES_ACTIVE | dev | No | 環境 |
| DB_URL | jdbc:postgresql://... | No | 接続先 |
| DB_USERNAME | cf_app | Yes | DB利用者 |
| DB_PASSWORD | *** | Yes | Secrets Manager |
| COGNITO_ISSUER | https://cognito-idp... | No | JWT issuer |
| S3_BUCKET | cf-dev-files | No | ファイルBucket |
| PAYMENT_SECRET | *** | Yes | 決済署名 |
| OTEL_EXPORTER_OTLP_ENDPOINT | http://collector:4317 | No | Telemetry |
| AI_FEATURE_ENABLED | false | No | 本番機能では原則false。開発ツール利用とは別。 |

## 13.2 Spring Profile

| Profile | 外部連携 | DB | 用途 |
| --- | --- | --- | --- |
| local | LocalStack/Mock | Docker PostgreSQL | 個人開発 |
| test | Mock/WireMock | Testcontainers | 自動試験 |
| dev | AWS Dev/Sandbox | RDS Dev | 統合 |
| staging | 本番相当Sandbox | RDS Staging | 受入・性能 |
| production | 本番相当設定 | RDS Production相当 | 教育デモ・運用訓練 |

## 13.3 Container

```dockerfile
FROM amazoncorretto:25 AS runtime
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY app.jar /app/app.jar
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

- root実行を禁止する。
- イメージへ秘密情報を含めない。
- health endpointはliveness/readinessを分離する。
- 固定メモリ値よりContainer limitを考慮した割合指定を基本とする。
- SBOMと脆弱性Scan結果を保存する。

## 13.4 CI Pipeline

```text
PR:
  backend-format -> compile -> unit -> architecture -> integration
  frontend-lint -> typecheck -> unit -> build
  openapi-diff -> dependency-scan -> secret-scan -> codeql
  AI review(optional/required by risk) -> human approval

main:
  build image -> SBOM -> image scan -> push ECR
  terraform plan -> deploy dev -> smoke test

staging/production-equivalent:
  manual approval -> deploy -> migration -> smoke -> monitor
```

## 13.5 DB Migration

| 段階 | 処理 |
| --- | --- |
| Pre-deploy | Migration互換性とバックアップ確認 |
| Deploy-1 | 後方互換DDLを適用 |
| Deploy-2 | 新旧両対応アプリを配置 |
| Data migration | 必要ならBatchで段階移行 |
| Cleanup | 旧列・旧処理は次回以降に削除 |
| Rollback | アプリRollback可能なDDLを優先。破壊DDLは即時Rollback対象外。 |

# 14. テスト詳細設計

## 14.1 テスト責務

| 層 | 技術 | 責務 |
| --- | --- | --- |
| Domain Unit | JUnit 5/Kotest | 不変条件、状態遷移、Value Object |
| Application Unit | MockK/Mockito | 認可、Port呼出し、Tx外の制御 |
| Repository Integration | Testcontainers PostgreSQL | JPA/MyBatis/制約/Flyway |
| Web Integration | Spring Boot Test | HTTP、Security、Validation、Problem Details |
| External Adapter | WireMock/LocalStack | Timeout、Retry、署名、変換 |
| Frontend Unit | Vitest | Component/Hook/validation |
| E2E | Playwright | 主要業務フロー |
| Architecture | ArchUnit/Spring Modulith | 依存境界 |
| Mutation | PIT等 | 重要ルールのテスト有効性 |

## 14.2 Projectテスト観点

| Case ID | 条件 | 期待 |
| --- | --- | --- |
| PJ-U-001 | 有効な下書き作成 | DRAFT、ProjectCreated |
| PJ-U-002 | 目標金額999 | Validation error |
| PJ-U-003 | 終了＝開始 | Validation error |
| PJ-U-004 | DRAFTで必須完備 | REVIEW_REQUESTED |
| PJ-U-005 | 画像なしで申請 | PROJECT_INCOMPLETE |
| PJ-U-006 | PUBLISHEDを更新 | PROJECT_INVALID_STATE |
| PJ-I-001 | version競合 | 409、既存データ不変 |
| PJ-I-002 | 申請Tx途中でAudit失敗 | 全変更Rollback |

## 14.3 支援・決済テスト観点

| Case ID | 条件 | 期待 |
| --- | --- | --- |
| FD-U-001 | 公開期間内・在庫あり | Support/Payment生成 |
| FD-U-002 | 同一Idempotency-Key同一Body | 同一応答 |
| FD-U-003 | 同一Key異なるBody | 409 IDEMPOTENCY_CONFLICT |
| FD-I-001 | 残数1に同時2要求 | 一方成功、一方SOLD_OUT |
| PY-I-001 | Webhook重複 | 二重計上なし、204 |
| PY-I-002 | 同event IDでPayload差異 | ERROR、アラート |
| PY-I-003 | 成功Webhook後に失敗Webhook | 状態を戻さずConflict記録 |
| RF-I-001 | 返金API Timeout | RETRY_WAIT、重複要求なし |

## 14.4 E2Eシナリオ

| E2E ID | シナリオ |
| --- | --- |
| E2E-001 | OWNERが作成→保存→プレビュー→審査申請 |
| E2E-002 | REVIEWERが開始→差戻し→OWNER修正→再申請→承認 |
| E2E-003 | 承認済みProjectが開始時刻に公開される |
| E2E-004 | SUPPORTERが支援しWebhookで成功確定する |
| E2E-005 | 数量限定リターンが売切れとなる |
| E2E-006 | 募集不成立→返金要求→返金成功 |
| E2E-007 | ADMINがロール変更し監査ログを確認する |
| E2E-008 | 同時更新で競合メッセージを表示する |

## 14.5 テストデータ

- Builder/Object Motherを `test-support` に置き、ドメイン上有効な標準データを生成する。
- 異常値は標準Builderを部分変更して作る。
- 実在メール、氏名、住所、決済情報を使用しない。
- 時刻依存テストはFixedClockを使用する。
- 並行性テストは実PostgreSQL上で実行する。

## 14.6 完了基準

| 対象 | 基準 |
| --- | --- |
| Domain | 主要状態遷移・不変条件100%、行90%以上 |
| Application | 主要分岐・例外・Port呼出し検証 |
| API | 認証・認可・入力・エラー形式を含む |
| DB | DDLを空DBから適用し全Integration成功 |
| Frontend | Typecheck、主要Component、E2E成功 |
| Security | High/Critical指摘0 |
| AI生成変更 | 人間がテスト不足と誤生成を確認済み |


# 15. AI駆動開発詳細設計

## 15.1 AIの役割分担

| 工程 | AI | 人間 |
| --- | --- | --- |
| 要件確認 | 曖昧点、矛盾、受入条件候補 | 業務判断、顧客確認、確定 |
| 設計 | クラス/API/テスト案、影響範囲抽出 | 境界、トレードオフ、採否 |
| 実装 | 限定されたIssue単位のコード生成 | 差分理解、修正、承認 |
| 試験 | 正常・異常・境界・並行ケース案 | 有効性、欠落、期待値確認 |
| レビュー | バグ、脆弱性、設計逸脱候補 | 真偽判定、修正判断 |
| 運用 | ログから原因候補と手順案 | 本番操作、原因確定、復旧判断 |

## 15.2 AGENTS.md必須事項

- 目的、上位要件、対象Issue、完了条件。
- 許可されたモジュール・パスと変更禁止パス。
- DDD境界、依存方向、トランザクション規則。
- Corretto 25、Gradle、Kotlin/Java、Next.jsのバージョン。
- ビルド、テスト、Lint、Scanの実行コマンド。
- 秘密情報・個人情報・実データを入力しない規則。
- 推測で仕様を補わず、未確定事項を質問またはTODOとして残す規則。

## 15.3 AIタスクテンプレート

```text
目的:
対象Issue / 要件ID:
変更可能パス:
変更禁止パス:
入力仕様:
出力仕様:
DDD上の所属:
必須テスト:
非機能・セキュリティ制約:
完了条件:
不明点:
```

## 15.4 AI変更リスク分類

| Risk | 例 | 必須レビュー |
| --- | --- | --- |
| Low | 文言、テストデータ、内部Refactor | 人間1名＋CI |
| Medium | UseCase、画面、通常API、Query | 人間1名、関連テスト、AIレビュー推奨 |
| High | 認証、認可、決済、返金、DB Migration、Concurrency | 人間2名または専門レビュー、独立AIレビュー、結合/E2E |
| Critical | 秘密管理、IAM、本番Deploy、監査削除 | AI単独変更禁止、責任者承認 |

## 15.5 AI生成コードのレビュー観点

- 存在しないAPI、非推奨API、バージョン不整合がないか。
- Controllerへ業務ロジックを置いていないか。
- トランザクション内で外部APIを呼んでいないか。
- 認可、所有権、状態遷移、冪等性、並行性が欠落していないか。
- テストが実装の写経になり、誤った仕様を固定していないか。
- ログ、例外、レスポンスへ秘密・個人情報を出していないか。
- ライセンス不明コードや長い外部コード断片を混入していないか。
- 設計書、OpenAPI、Flyway、ADRが必要なのに更新されていないか。

## 15.6 AI利用記録

| 項目 | 記録内容 |
| --- | --- |
| Tool/Model | Copilot/Codex/Claude Code等 |
| Task | Issue/PR ID |
| Changed paths | 対象ファイル一覧 |
| Prompt | 原則本文を保存せずhashまたは要約 |
| Result | 採用/修正採用/却下 |
| Reviewer | 人間承認者 |
| Risk | Low/Medium/High/Critical |
| Tests | 実行コマンド・結果 |

## 15.7 禁止操作

- mainへの直接push、無人merge、本番相当環境への自動承認。
- Secrets Manager、DB、本番相当AWSへの管理者権限付与。
- 実在顧客情報、Token、Cookie、秘密鍵をプロンプトへ含める。
- テストを通すための仕様無断変更、Security設定の無効化。
- 要件不明点を推測して不可逆なDB/API変更を行う。

# 16. トレーサビリティ・実装引継ぎ

## 16.1 設計対応表

| 要件/機能 | 画面 | API | UseCase | 主要テーブル | 主要テスト |
| --- | --- | --- | --- | --- | --- |
| プロジェクト作成・更新 | SCR-021 | API-PJ-003/004 | UC-PJ-001/002 | project,reward_plan | PJ-U/I |
| 審査申請・判断 | SCR-023/031 | API-PJ-005,RV-003～006 | UC-PJ-003,RV-001～003 | review_request,review_history | E2E-001/002 |
| 支援 | SCR-040/041/042 | API-FD-001 | UC-FD-001 | support,support_item,payment,idempotency_record | FD-U/I,E2E-004/005 |
| 決済Webhook | なし | API-PY-001 | UC-PY-001 | payment,payment_webhook_event,outbox_event | PY-I |
| 返金 | SCR-061 | API-RF-001/002 | UC-RF-001 | refund,payment,support | RF-I,E2E-006 |
| ファイル | SCR-021/031 | API-FL-001/002 | UC-FL-001 | file_object | Adapter Integration |
| 会員・ロール | SCR-070 | API-AD-002 | UC-AD-001 | app_user,user_role,audit_log | Security Integration |
| 監査 | SCR-071 | API-AU-001/002 | SearchAuditQuery | audit_log,ai_activity_log | E2E-007 |

## 16.2 実装順序

1. Shared Kernel、Error、Clock、ID、Money
2. Project集約とDomain Unit Test
3. Project Repository/Flyway/API/画面
4. Review集約と審査フロー
5. File/S3 Adapter
6. Funding/Paymentの内部モデルと冪等性
7. Payment Sandbox/Webhook/Outbox
8. Notification/Refund/Batch
9. Identity/Admin/Audit
10. 監視、CI/CD、E2E、運用手順

## 16.3 詳細設計完了条件

- 主要クラス・UseCase・API・DB・Batchの設計が本書へ記載されている。
- 上位要件と基本設計のIDへ追跡できる。
- 状態遷移、認可、冪等性、排他、例外、監査の実装方針が明確である。
- 実装者が重要な仕様を推測せず、Issueへ分割できる。
- AIへ依頼可能な単位と禁止範囲が明確である。
- 未確定事項はADR候補または課題一覧へ記録されている。

## 16.4 未確定・ADR候補

| ADR候補 | 判断内容 | 初期推奨 |
| --- | --- | --- |
| ADR-001 | Gradleマルチモジュール開始時期 | 教育初期はpackage、3か月目以降module化 |
| ADR-002 | JPA EntityとDomain Modelの分離 | 重要集約は分離、単純CRUDは同一も可 |
| ADR-003 | Next.js BFFの配置 | 認証・セッション・集約APIに限定 |
| ADR-004 | 決済同期/非同期UI | 非同期確定を正とする |
| ADR-005 | Rich Text形式 | 制限付きMarkdownを第一候補 |
| ADR-006 | ウイルススキャン | 教育環境はMock、本番想定は非同期Scanner |
| ADR-007 | Terraform/OpenTofu | 案件市場性からTerraformを初期採用 |

# 付録A. Kotlin/Java実装例

## A.1 Project Application Service

```kotlin
@Service
class SubmitProjectForReviewService(
    private val projectRepository: ProjectRepository,
    private val reviewRepository: ReviewRepository,
    private val submissionPolicy: ProjectSubmissionPolicy,
    private val outboxRepository: OutboxRepository,
    private val auditPort: AuditPort,
    private val clock: Clock,
    private val idGenerator: UlidGenerator
) : SubmitProjectForReviewUseCase {

    @Transactional
    override fun execute(
        command: SubmitProjectForReviewCommand,
        currentUser: CurrentUser,
        audit: AuditContext
    ): SubmitProjectForReviewResult {
        val project = projectRepository.findById(command.projectId)
            ?: throw ProjectNotFoundException(command.projectId)
        project.requireOwnedBy(currentUser.userId)
        project.requireVersion(command.expectedVersion)
        submissionPolicy.validateOrThrow(project)

        val review = ReviewRequest.create(
            ReviewId.newId(idGenerator), project.id, clock.instant()
        )
        val event = project.submitForReview(review.id, clock.instant())

        projectRepository.save(project)
        reviewRepository.save(review)
        outboxRepository.append(event, audit.correlationId)
        auditPort.record(audit, "PROJECT_SUBMIT_REVIEW", project.id.value)
        return SubmitProjectForReviewResult(review.id, project.status)
    }
}
```

## A.2 Java Adapter Interface Example

```java
public interface PaymentProviderClient {
    PaymentProviderResult createPayment(CreatePaymentRequest request);
    PaymentProviderStatus getPayment(String providerPaymentId);
    RefundProviderResult requestRefund(RefundProviderRequest request);
    VerifiedWebhook verifyWebhook(Map<String, String> headers, byte[] rawBody);
}
```

# 付録B. Definition of Done

1. 要件ID・基本設計ID・詳細設計IDがIssue/PRに記載されている。
2. Corretto 25でビルドし、Java/Kotlin JVMターゲットが一致している。
3. DDD境界と依存規則を満たす。
4. 認可、所有権、状態、冪等性、排他を必要箇所で実装している。
5. 単体、結合、E2Eの必要試験が追加されている。
6. OpenAPI、Flyway、ADR、運用手順を必要に応じ更新している。
7. ログへ機微情報を出していない。
8. CI、静的解析、依存・Container Scanが成功している。
9. AI生成部分を人間が説明・レビューできる。
10. High/Critical変更は追加承認を受けている。
11. Rollbackまたは再処理方法が定義されている。
12. 実装者が変更内容と残存リスクを説明できる。

# English Summary

This detailed design document translates the approved requirements and basic design into implementation-level specifications for the CF-Training system. It defines modules, packages, domain aggregates, application use cases, API requests and responses, screen behavior, PostgreSQL tables and indexes, asynchronous workers, security controls, exception handling, monitoring, deployment, testing, and AI-assisted development governance.

The backend runs on Amazon Corretto 25 with Kotlin as the primary language and Java as a secondary language. Spring Boot 4.1, DDD, a modular monolith, PostgreSQL 18, Next.js 16, AWS, Terraform, and GitHub Actions are used. AI tools may analyze, generate, test, and review changes, but human approval is mandatory for requirements, architecture, code adoption, merges, and production-equivalent deployment.

