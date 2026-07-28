# クラウドファンディング型教育・実践開発システム
# 基本設計書

| 項目 | 内容 |
|---|---|
| 文書ID | BD-CF-001 |
| 版数 | 1.3 |
| 作成日 | 2026-07-20 |
| 文書状態 | 実装開始用・暫定確定版 |
| 上位文書 | 要件定義書・要件確認書・技術選定書 |
| 対象構成 | Amazon Corretto 25 / Kotlin・Java / Spring Boot 4.1 / DDD / Next.js 16 / AWS / AI駆動開発 |
| 想定読者 | 開発者、レビュー担当者、教育担当者、運用担当者、SES営業担当者 |

---

# 0. 文書管理

## 0.1 目的

本書は、上位文書で確定した要件および技術選定を、実装・詳細設計・試験へ接続できる基本設計へ展開するものである。

本書では次を定義する。

- システム全体構成と責務分担
- 利用者、権限、業務フロー、状態遷移
- DDDに基づく境界づけられたコンテキストとモジュール
- 画面、API、データ、バッチ、外部連携の基本仕様
- 認証・認可、監査、ログ、監視、例外処理
- AWS配置、CI/CD、AI駆動開発の統制
- 詳細設計、実装、試験へ引き渡す制約と完了条件

## 0.2 適用範囲

対象は、教育用のクラウドファンディング型Webシステムである。実際の資金移動は行わず、決済Sandboxまたは決済モックを利用する。

初期リリースでは、会員、プロジェクト、審査、支援、決済、返金、通知、ファイル、監査の機能を対象とする。

## 0.3 前提と制約

| ID | 前提・制約 |
|---|---|
| A-01 | JVMはAmazon Corretto 25に統一する。 |
| A-02 | バックエンドはKotlin主体、Java併用とする。 |
| A-03 | Spring Boot 4.1系、Spring Framework 7系を採用する。 |
| A-04 | 初期構成はモジュラーモノリスとし、マイクロサービス分割は行わない。 |
| A-05 | DBはPostgreSQL 18を単一インスタンスで利用する。 |
| A-06 | フロントエンドはTypeScript、React 19.2、Next.js 16.2とする。 |
| A-07 | AWS上の標準配置はECS Fargate、RDS、S3、SES、Cognitoとする（SQSは不採用。ADR-0008）。 |
| A-08 | AI生成物は人間がレビュー・承認し、AI単独でマージ・本番反映しない。 |
| A-09 | 実在顧客情報、秘密情報、実決済情報を教育環境・AIへ投入しない。 |
| A-10 | DDDは業務ルールが存在する領域に適用し、単純CRUDへ過剰適用しない。 |

## 0.4 変更履歴

| 版数 | 日付 | 変更内容 |
|---|---|---|
| 1.0 | 2026-07-20 | 初版作成。画面、API、DB、DDD、AI統制、AWS配置を定義。 |
| 1.1 | 2026-07-20 | §4.6 主要ドメインイベント名を詳細設計・実装へ統一（ProjectReviewRequested→ProjectSubmittedForReview、SupportAccepted→SupportRequested、PaymentCompleted→PaymentSucceeded）。本書を上位・正とする原則の例外として、名称のみ下位へ合わせた。`ProjectFailed` の分割/統合は未確定として明記。 |
| 1.2 | 2026-07-20 | 募集終了イベントを成立・不成立で分割することを確定（§4.6 に `ProjectSucceeded` を追加、§8.1 BAT-003 の購読対象を `ProjectFailed` と明記）。詳細設計・実装側の統合イベント `ProjectFundingClosed` は本書に合わせて分割済み。 |
| 1.3 | 2026-07-28 | **実装を正として同期**（コード読解による突き合わせ）。§8.1: BAT-004/005 の起動を「SQS」から実装どおりの定期実行へ、BAT-006 の配送先をアプリ内Handlerへ確定（ADR-0008）、BAT-009 の概要を実出力先の確定に合わせて具体化（ADR-0009）、実装済みの **BAT-010 冪等記録削除**を追加。A-07・システム構成図・External Adapter・§12 テスト戦略から **SQS を削除**（ADR-0008）。§7.7 に監査アーカイブのS3側保持を追記（ADR-0009）。§9.1 に会員の初回登録手段を追記（ADR-0007）。 |

## 0.5 章構成

1. システム概要
2. 利用者・権限設計
3. 業務フロー・状態遷移
4. DDD・アプリケーション構造
5. 画面基本設計
6. API基本設計
7. データ基本設計
8. バッチ・非同期処理設計
9. 外部インターフェース設計
10. 認証・認可・セキュリティ設計
11. エラー・ログ・監視・監査設計
12. 非機能・インフラ基本設計
13. CI/CD・AI駆動開発設計
14. テスト・移行・運用設計
15. トレーサビリティと詳細設計引継ぎ

---

# 1. システム概要

## 1.1 システム名称

**クラウドファンディング型教育・実践開発システム**

略称は `CF-Training` とする。略称はコード、ログ、CI/CD、AWSリソースの接頭辞に使用できるが、外部公開名称としては使用しない。

## 1.2 システム目的

本システムは、クラウドファンディング業務を題材として、次の実務能力を育成・検証する。

- 要件確認と受入条件定義
- DDDによる業務モデル設計
- Kotlin・Java混在のSpring Boot開発
- Next.jsによるフロントエンド開発
- REST API、認証、決済、非同期処理
- PostgreSQL、Docker、AWS、CI/CD
- AIによる分析・生成と人間による検証
- 障害対応、監視、改修、運用

## 1.3 システム構成

```text
[利用者ブラウザ]
       |
       | HTTPS
       v
[CloudFront / ALB]
       |
       +----------------------+
       |                      |
       v                      v
[Next.js Web/BFF]       [Spring Boot API]
       |                      |
       | OIDC                 +--> [PostgreSQL / RDS]
       +--> [Cognito]          +--> [S3]
                              +--> [SES]
                              +--> [決済Sandbox]
                              +--> [CloudWatch / OpenTelemetry]

[GitHub]
   +--> GitHub Actions --> ECR --> ECS Fargate
   +--> Copilot / Codex / 任意の独立AIレビュー
```

## 1.4 論理コンポーネント

| コンポーネント | 主責務 |
|---|---|
| Web/BFF | 画面表示、入力制御、セッション、API集約、CSRF対策 |
| Backend API | ユースケース実行、認可、トランザクション、ドメイン呼出し |
| Domain Modules | 業務ルール、状態遷移、整合性、ドメインイベント |
| Persistence Adapter | JPA/MyBatis、DBアクセス、Outbox永続化 |
| External Adapter | Cognito、S3、SES、決済Sandboxとの連携 |
| Batch/Worker | 期限終了、通知、返金、再処理、Outbox配送 |
| Observability | ログ、メトリクス、トレース、アラート |
| AI Development Controls | AGENTS.md、プロンプト、AI利用記録、承認ゲート |

## 1.5 環境区分

| 環境 | 用途 | データ | デプロイ |
|---|---|---|---|
| Local | 個人開発、単体・結合試験 | 合成データ | 開発者操作 |
| Dev | チーム統合、機能確認 | 合成データ | main反映後に自動 |
| Staging | 受入、性能、運用確認 | 本番相当の匿名・合成データ | 承認付き |
| Production相当 | 教育デモ、運用訓練 | 合成データのみ | 手動承認付き |

---

# 2. 利用者・権限設計

## 2.1 利用者区分

| ロール | 説明 |
|---|---|
| GUEST | 未認証利用者。公開情報のみ参照する。 |
| SUPPORTER | 支援者。支援申込、履歴確認、取消可能範囲の操作を行う。 |
| OWNER | 起案者。プロジェクト作成、編集、審査申請を行う。 |
| REVIEWER | 審査担当者。審査、差戻し、承認、却下を行う。 |
| OPERATOR | 運用担当者。支援、返金、通知、障害再処理を行う。 |
| ADMIN | システム管理者。会員、ロール、全体設定を管理する。 |
| AUDITOR | 監査担当者。監査ログを参照する。 |
| DEVELOPER | 開発・検証環境で運用支援を行う。 |
| AI_AGENT | 開発支援のみ。業務データ・本番権限を持たない。 |

## 2.2 権限マトリクス

記号：`R` 参照、`C` 作成、`U` 更新、`X` 業務実行、`-` 不可。

| 機能 | GUEST | SUPPORTER | OWNER | REVIEWER | OPERATOR | ADMIN | AUDITOR |
|---|---|---|---|---|---|---|---|
| 公開プロジェクト | R | R | R | R | R | R | R |
| プロフィール | - | R/U | R/U | R/U | R/U | R/U | R |
| プロジェクト作成 | - | - | C/U | R | R | R/U | R |
| 審査申請 | - | - | X | R | R | R | R |
| 審査判断 | - | - | - | X | R | R | R |
| 支援申込 | - | X | X | X | X | X | R |
| 支援取消 | - | X | X | X | X | X | R |
| 返金実行 | - | - | - | - | X | X | R |
| 会員・ロール管理 | - | - | - | - | R | R/U | R |
| 監査ログ | - | 自分のみ | 自分のみ | 業務範囲 | 業務範囲 | R | R |
| AI利用記録 | - | - | - | - | R | R | R |

## 2.3 認可判定原則

1. 画面の表示制御だけで認可を完結させない。
2. Backend APIでロールとリソース所有権を検証する。
3. 所有権判定は、ログインIDではなく内部UserIdで行う。
4. 管理者権限は通常業務ロールと分離する。
5. 重要操作は再認証または強い認証を要求できる構造とする。
6. AI_AGENTにはAWS、DB、mainブランチ、本番秘密情報への権限を与えない。

---

# 3. 業務フロー・状態遷移

## 3.1 プロジェクト登録・審査フロー

```text
起案者：下書き作成
  -> 入力・保存
  -> プレビュー
  -> 審査申請
審査担当者：受付
  -> 審査
     -> 承認 -> 公開待ち -> 公開中
     -> 差戻し -> 修正中 -> 再申請
     -> 却下 -> 終了
```

## 3.2 プロジェクト状態

| 状態コード | 状態名 | 説明 | 主要遷移 |
|---|---|---|---|
| DRAFT | 下書き | 起案者が編集可能 | REVIEW_REQUESTED、CANCELLED |
| REVIEW_REQUESTED | 審査申請済 | 審査待ち | UNDER_REVIEW、WITHDRAWN |
| UNDER_REVIEW | 審査中 | 審査担当者が確認中 | APPROVED、RETURNED、REJECTED |
| RETURNED | 差戻し | 起案者が修正可能 | REVIEW_REQUESTED、CANCELLED |
| APPROVED | 承認済 | 公開条件待ち | PUBLISHED、CANCELLED |
| PUBLISHED | 公開中 | 支援受付中 | SUCCEEDED、FAILED、SUSPENDED |
| SUSPENDED | 公開停止 | 運用判断で停止 | PUBLISHED、CANCELLED |
| SUCCEEDED | 成立 | 募集成立 | SETTLED |
| FAILED | 不成立 | 募集不成立 | REFUNDING |
| REFUNDING | 返金中 | 返金処理中 | REFUNDED、REFUND_ERROR |
| REFUNDED | 返金完了 | 返金完了 | - |
| SETTLED | 精算完了 | 起案者への精算完了 | - |
| REJECTED | 却下 | 審査却下 | - |
| CANCELLED | 取消 | 起案者または管理者が取消 | - |

## 3.3 状態遷移制約

- `PUBLISHED` への遷移には承認済み、公開開始日時到達、必須画像登録済みが必要。
- `SUCCEEDED` は募集終了時に目標条件を満たす場合だけ許可する。
- `FAILED` からは返金対象の支援が存在する場合に `REFUNDING` へ遷移する。
- `RETURNED` では審査コメントを必須とする。
- `REJECTED`、`CANCELLED`、`REFUNDED`、`SETTLED` は原則終端状態とする。
- すべての重要遷移で、変更前状態、変更後状態、実行者、理由、相関IDを監査記録する。

## 3.4 支援・決済フロー

```text
支援者：支援金額とリターン選択
  -> 確認
  -> 支援申込作成(PENDING)
  -> 決済Sandboxへ要求
  -> 決済受付
  -> Webhook受信
     -> 成功：PAID
     -> 失敗：PAYMENT_FAILED
     -> 保留：PENDINGを維持して照会・再処理
```

## 3.5 支援状態

| 状態 | 説明 |
|---|---|
| PENDING | 支援受付済み、決済確定前 |
| PAID | 決済成功 |
| PAYMENT_FAILED | 決済失敗 |
| CANCEL_REQUESTED | 取消要求済み |
| CANCELLED | 取消完了 |
| REFUND_REQUESTED | 返金要求済み |
| REFUNDING | 返金処理中 |
| REFUNDED | 返金完了 |
| REFUND_FAILED | 返金失敗 |

## 3.6 冪等性設計

- 支援申込APIは `Idempotency-Key` を必須とする。
- 同一利用者・同一キーの再要求は、初回結果を返す。
- 決済Webhookは外部イベントIDを一意制約で管理する。
- 返金要求は支援IDと返金理由区分から業務一意キーを生成する。
- 非同期処理はメッセージIDと処理種別で重複処理を防止する。

---

# 4. DDD・アプリケーション構造

## 4.1 境界づけられたコンテキスト

| コンテキスト | 主な責務 | 所有データ |
|---|---|---|
| Identity & Access | 会員、ロール、認証主体の対応 | user、role、user_role |
| Project Management | プロジェクト、募集条件、リターン | project、reward_plan |
| Review | 審査申請、判断、コメント | review_request、review_history |
| Funding | 支援申込、取消、成立判定 | support、support_item |
| Payment | 決済、Webhook、返金 | payment、refund、webhook_event |
| File Management | ファイルメタデータ、S3キー | file_object |
| Notification | 通知要求、送信、再送 | notification、notification_delivery |
| Audit | 操作・変更・AI利用の監査 | audit_log、ai_activity_log |

## 4.2 モジュール依存

```text
identity       <- project
identity       <- review
identity       <- funding
project        <- review
project        <- funding
funding        <- payment
project        <- file
review/funding/payment -> notification
all important modules  -> audit event
```

依存は、公開されたApplication APIまたはドメインイベントを介する。別モジュールのRepositoryやテーブルへ直接アクセスしない。

## 4.3 レイヤー構造

```text
adapter.in.web
    -> application.usecase
        -> domain.model / domain.service
            -> domain.repository (Port)
        -> adapter.out.persistence / external (Adapter)
```

## 4.4 Kotlin・Java配置方針

| 対象 | 主言語 | 理由 |
|---|---|---|
| Project、Review、Fundingのドメイン | Kotlin | sealed class、Value Object、null安全性を活用 |
| Application UseCase | Kotlin | 型安全なコマンド・結果型を表現 |
| Identity & Access | Java | Java案件向け教育、Spring Securityとの接続経験 |
| External Adapter | Java | Java SDK・既存ライブラリとの接続訓練 |
| Batch | JavaまたはKotlin | 担当者の教育段階に応じて選択 |
| Test | Kotlin中心 | テスト記述性を重視。Java版演習も残す |

## 4.5 ドメインモデル設計規則

- IDは型付きValue Objectとし、文字列のままドメイン層へ流さない。
- 金額は通貨を含むMoney型で表現し、浮動小数点を使用しない。
- 日時はUTCで保持し、表示時にAsia/Tokyoへ変換する。
- 状態変更はsetterではなく、意味のあるドメインメソッドで行う。
- 集約更新時はバージョン列による楽観ロックを使用する。
- Domain Eventは業務上意味のある完了事実として命名する。

## 4.6 主要ドメインイベント

| イベント | 発生条件 | 主な購読者 |
|---|---|---|
| ProjectSubmittedForReview | 審査申請完了 | Review、Notification、Audit |
| ProjectApproved | 審査承認 | Project、Notification、Audit |
| ProjectReturned | 差戻し | Notification、Audit |
| ProjectPublished | 公開開始 | Notification、Audit |
| SupportRequested | 支援申込受付 | Payment、Audit |
| PaymentSucceeded | 決済成功 | Funding、Notification、Audit |
| PaymentFailed | 決済失敗 | Funding、Notification、Audit |
| ProjectSucceeded | 募集成立 | Notification、Audit |
| ProjectFailed | 募集不成立 | Payment、Notification、Audit |
| RefundCompleted | 返金成功 | Funding、Notification、Audit |

イベント名は詳細設計 §4.1／§4.3／§4.4 および実装と一致させる（版数1.1で統一）。

募集終了時のイベントは成立・不成立で分割する（版数1.2で確定）。両者は §3.2 の状態
（SUCCEEDED→SETTLED / FAILED→REFUNDING）も後続処理も異なり、購読側が payload を解釈せず
イベント種別だけで振り分けられるようにするため。BAT-003 返金対象作成は `ProjectFailed`
のみを購読する（§8.1）。

## 4.7 トランザクション境界

- 1ユースケース、1集約更新を基本とする。
- 複数モジュールを同一トランザクションで直接更新しない。
- モジュール間はイベントで連携し、Outboxへ同一トランザクションで記録する。
- 外部API呼出しをDBトランザクション内で長時間保持しない。
- 決済結果はWebhookまたは照会結果で確定する。

---

# 5. 画面基本設計

## 5.1 画面共通方針

- PC幅を主対象とし、スマートフォンでも主要操作を可能とする。
- App Routerを利用し、Server Componentを基本とする。
- 入力画面、対話操作、ブラウザAPI利用箇所だけClient Componentとする。
- 画面固有状態とサーバー状態を分離する。
- フォームはReact Hook Form、検証はZodを使用する。
- Backendの業務Validationを正とし、フロント検証は利用性向上のために重複実装する。
- エラー時に入力内容を可能な範囲で保持する。
- アクセシビリティとして、ラベル、キーボード操作、フォーカス、エラー関連付けを考慮する。

## 5.2 画面一覧

| 画面ID | 画面名 | 主利用者 | 概要 |
|---|---|---|---|
| SCR-001 | ログイン | 全認証利用者 | Cognitoを介してログインする。 |
| SCR-002 | アクセス拒否 | 全利用者 | 権限不足を表示する。 |
| SCR-010 | プロジェクト検索 | 全利用者 | 公開中プロジェクトを検索する。 |
| SCR-011 | プロジェクト詳細 | 全利用者 | 詳細、進捗、リターンを表示する。 |
| SCR-020 | プロジェクト一覧（起案者） | OWNER | 自分のプロジェクトと状態を表示する。 |
| SCR-021 | プロジェクト編集 | OWNER | 基本情報、募集条件、リターンを編集する。 |
| SCR-022 | プレビュー | OWNER | 公開時表示を確認する。 |
| SCR-023 | 審査申請確認 | OWNER | 申請条件と確認事項を表示する。 |
| SCR-030 | 審査一覧 | REVIEWER | 審査対象を検索・割当する。 |
| SCR-031 | 審査詳細 | REVIEWER | 内容、証跡、履歴を確認し判断する。 |
| SCR-040 | 支援入力 | SUPPORTER | 金額、リターン、連絡情報を入力する。 |
| SCR-041 | 支援確認 | SUPPORTER | 支援内容を確認する。 |
| SCR-042 | 支援結果 | SUPPORTER | 受付・成功・失敗を表示する。 |
| SCR-050 | マイページ | 認証利用者 | プロフィールと利用履歴を表示する。 |
| SCR-051 | 支援履歴 | SUPPORTER | 支援・決済・返金状態を表示する。 |
| SCR-060 | 支援管理 | OPERATOR | 支援検索、詳細、再処理を行う。 |
| SCR-061 | 返金管理 | OPERATOR | 返金要求、承認、再実行を行う。 |
| SCR-070 | 会員・ロール管理 | ADMIN | ロール付与・停止を行う。 |
| SCR-071 | 監査ログ検索 | ADMIN/AUDITOR | 重要操作とAI利用を検索する。 |
| SCR-080 | システムエラー | 全利用者 | 相関ID付きでエラーを表示する。 |

## 5.3 画面遷移

```text
SCR-010 プロジェクト検索
  -> SCR-011 プロジェクト詳細
       -> SCR-040 支援入力
            -> SCR-041 支援確認
                 -> SCR-042 支援結果

SCR-020 起案者一覧
  -> SCR-021 編集
       -> SCR-022 プレビュー
            -> SCR-023 審査申請確認
                 -> SCR-020

SCR-030 審査一覧
  -> SCR-031 審査詳細
       -> 承認 / 差戻し / 却下
       -> SCR-030
```

## 5.4 SCR-021 プロジェクト編集

### 入力項目

| 項目 | 型・制約 | 必須 | 備考 |
|---|---|---|---|
| タイトル | 1～100文字 | 必須 | 禁止語・HTMLを除去 |
| 概要 | 1～300文字 | 必須 | 一覧表示用 |
| 本文 | 1～20,000文字 | 必須 | Markdownまたは制限付きRich Text |
| 目標金額 | 1,000～100,000,000円 | 必須 | 円単位、整数 |
| 募集開始日時 | 日時 | 必須 | 承認後かつ未来日時 |
| 募集終了日時 | 日時 | 必須 | 開始後、最大180日 |
| 募集方式 | All-or-Nothing / All-in | 必須 | 初期値はAll-or-Nothing |
| メイン画像 | JPG/PNG/WebP | 必須 | 最大10MB |
| リターン | 1件以上 | 必須 | 金額、名称、数量、説明 |

### ボタン

- 下書き保存
- プレビュー
- 審査申請へ
- 取消

### 制御

- 審査申請後は編集不可。ただし差戻し時は編集可能。
- 同時編集を検出した場合、更新せず最新内容の再読込を促す。
- 保存成功後は更新日時と保存者を表示する。

## 5.5 SCR-031 審査詳細

### 表示項目

- プロジェクト全項目
- 起案者情報
- 添付ファイル
- 過去の審査履歴
- 差分表示
- チェックリスト
- 監査対象となる確認事項

### 操作

| 操作 | 必須入力 | 結果 |
|---|---|---|
| 審査開始 | なし | UNDER_REVIEWへ遷移 |
| 承認 | 確認チェック | APPROVEDへ遷移 |
| 差戻し | コメント | RETURNEDへ遷移 |
| 却下 | 理由区分、コメント | REJECTEDへ遷移 |

## 5.6 SCR-040 支援入力

- 支援可能な公開期間であることをサーバー側で再確認する。
- リターン数量上限を確認する。
- 金額と手数料の内訳を表示する。
- 支援確定ボタンの多重押下をUIで抑止し、APIでも冪等制御する。
- 決済確定を同期応答だけで判断しない。

## 5.7 共通メッセージ

| メッセージID | 種別 | 内容 |
|---|---|---|
| MSG-I-001 | 情報 | 保存しました。 |
| MSG-I-002 | 情報 | 審査申請を受け付けました。 |
| MSG-W-001 | 警告 | 他の利用者により更新されています。再読込してください。 |
| MSG-W-002 | 警告 | 決済結果を確認中です。履歴画面で状態を確認してください。 |
| MSG-E-001 | 入力 | 入力内容を確認してください。 |
| MSG-E-002 | 権限 | この操作を実行する権限がありません。 |
| MSG-E-003 | 状態 | 現在の状態ではこの操作を実行できません。 |
| MSG-E-999 | システム | 処理を完了できませんでした。問い合わせ時は相関IDをお伝えください。 |

---

# 6. API基本設計

## 6.1 共通仕様

| 項目 | 仕様 |
|---|---|
| プロトコル | HTTPS |
| 形式 | JSON / UTF-8 |
| API接頭辞 | `/api/v1` |
| 認証 | OIDCアクセストークンまたはBFFセッション |
| 日時 | ISO 8601 UTC、例 `2026-07-20T12:34:56Z` |
| 金額 | 整数の最小通貨単位。円は1円単位 |
| 相関ID | `X-Correlation-Id`。未指定時はサーバー採番 |
| 冪等キー | 重要作成系で `Idempotency-Key` を要求 |
| ページング | `page`、`size`、`sort` |
| API仕様 | OpenAPI 3.1で管理 |

## 6.2 正常応答例

```json
{
  "data": {
    "projectId": "prj_01J...",
    "status": "DRAFT"
  },
  "meta": {
    "correlationId": "c01...",
    "timestamp": "2026-07-20T12:34:56Z"
  }
}
```

## 6.3 エラー応答例

```json
{
  "type": "https://example.invalid/problems/validation-error",
  "title": "Validation failed",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "detail": "入力内容を確認してください。",
  "instance": "/api/v1/projects",
  "correlationId": "c01...",
  "errors": [
    {"field": "title", "code": "NotBlank", "message": "タイトルは必須です。"}
  ]
}
```

Problem Details形式を基本とする。内部例外名、SQL、スタックトレース、秘密情報は返却しない。

## 6.4 API一覧：プロジェクト・審査

| API ID | Method / Path | 認可 | 概要 |
|---|---|---|---|
| API-PJ-001 | GET `/projects` | 公開 | 公開プロジェクト検索 |
| API-PJ-002 | GET `/projects/{projectId}` | 公開/所有者 | 詳細取得 |
| API-PJ-003 | POST `/owner/projects` | OWNER | 下書き作成 |
| API-PJ-004 | PUT `/owner/projects/{projectId}` | OWNER/所有者 | 下書き更新 |
| API-PJ-005 | POST `/owner/projects/{projectId}/review-requests` | OWNER/所有者 | 審査申請 |
| API-PJ-006 | POST `/owner/projects/{projectId}/cancel` | OWNER/所有者 | 取消 |
| API-RV-001 | GET `/reviews` | REVIEWER | 審査一覧 |
| API-RV-002 | GET `/reviews/{reviewId}` | REVIEWER | 審査詳細 |
| API-RV-003 | POST `/reviews/{reviewId}/start` | REVIEWER | 審査開始 |
| API-RV-004 | POST `/reviews/{reviewId}/approve` | REVIEWER | 承認 |
| API-RV-005 | POST `/reviews/{reviewId}/return` | REVIEWER | 差戻し |
| API-RV-006 | POST `/reviews/{reviewId}/reject` | REVIEWER | 却下 |

## 6.5 API一覧：支援・決済・返金

| API ID | Method / Path | 認可 | 概要 |
|---|---|---|---|
| API-FD-001 | POST `/projects/{projectId}/supports` | SUPPORTER | 支援申込。冪等キー必須 |
| API-FD-002 | GET `/me/supports` | SUPPORTER | 自分の支援一覧 |
| API-FD-003 | GET `/me/supports/{supportId}` | SUPPORTER/所有者 | 支援詳細 |
| API-FD-004 | POST `/me/supports/{supportId}/cancel` | SUPPORTER/所有者 | 取消要求 |
| API-PY-001 | POST `/payments/webhooks` | 署名検証 | 決済Webhook受信 |
| API-PY-002 | POST `/operations/payments/{paymentId}/reconcile` | OPERATOR | 決済照合・再確認 |
| API-RF-001 | POST `/operations/supports/{supportId}/refunds` | OPERATOR | 返金要求 |
| API-RF-002 | POST `/operations/refunds/{refundId}/retry` | OPERATOR | 返金再実行 |

## 6.6 API一覧：会員・管理・監査

| API ID | Method / Path | 認可 | 概要 |
|---|---|---|---|
| API-US-001 | GET `/me` | 認証済 | 自分のプロフィール |
| API-US-002 | PUT `/me` | 認証済 | プロフィール更新 |
| API-AD-001 | GET `/admin/users` | ADMIN | 会員検索 |
| API-AD-002 | PUT `/admin/users/{userId}/roles` | ADMIN | ロール更新 |
| API-AD-003 | POST `/admin/users/{userId}/suspend` | ADMIN | 会員停止 |
| API-AU-001 | GET `/audit-logs` | ADMIN/AUDITOR | 監査ログ検索 |
| API-AU-002 | GET `/ai-activities` | ADMIN/AUDITOR | AI利用記録検索 |
| API-FL-001 | POST `/files/presigned-uploads` | 認証済 | S3アップロードURL発行 |
| API-FL-002 | POST `/files/{fileId}/complete` | 所有者 | アップロード完了登録 |

## 6.7 API実装方針

- ControllerはHTTP変換と入力検証に限定する。
- UseCaseはユースケース単位で定義する。
- Domain ObjectをそのままAPIへ公開しない。
- 入出力DTOはAPIバージョン単位で管理する。
- 参照APIはMyBatis等で専用Read Modelを返却してよい。
- 更新APIでは楽観ロックバージョンまたはETagを利用する。
- 外部公開APIの破壊的変更は新バージョンで提供する。

---

# 7. データ基本設計

## 7.1 データ設計方針

- 主キーはアプリケーション採番のULIDを基本とする。
- DB上は `varchar(26)` または適切な型で保持する。
- 監査用に `created_at`、`created_by`、`updated_at`、`updated_by` を付与する。
- 楽観ロック対象に `version` を付与する。
- 物理削除は限定し、業務データは状態または削除日時で管理する。
- 日時は `timestamp with time zone` を使用する。
- 金額は `bigint` を使用する。
- 個人情報を含む列はログへ出力しない。
- FlywayでDDLを管理する。

## 7.2 主要テーブル一覧

| テーブルID | テーブル名 | 所有コンテキスト | 概要 |
|---|---|---|---|
| TBL-001 | app_user | Identity | 内部利用者 |
| TBL-002 | role | Identity | ロールマスタ |
| TBL-003 | user_role | Identity | 利用者ロール |
| TBL-010 | project | Project | プロジェクト本体 |
| TBL-011 | reward_plan | Project | リターンプラン |
| TBL-012 | project_status_history | Project | 状態履歴 |
| TBL-020 | review_request | Review | 審査申請 |
| TBL-021 | review_history | Review | 審査判断履歴 |
| TBL-030 | support | Funding | 支援申込 |
| TBL-031 | support_item | Funding | 選択リターン |
| TBL-040 | payment | Payment | 決済 |
| TBL-041 | payment_webhook_event | Payment | Webhook受信履歴 |
| TBL-042 | refund | Payment | 返金 |
| TBL-050 | file_object | File | S3ファイルメタデータ |
| TBL-060 | notification | Notification | 通知要求 |
| TBL-061 | notification_delivery | Notification | 送信結果 |
| TBL-070 | outbox_event | Shared | 非同期配送待ちイベント |
| TBL-080 | audit_log | Audit | 重要操作監査 |
| TBL-081 | ai_activity_log | Audit | AI利用監査 |
| TBL-090 | idempotency_record | Shared | 冪等処理記録 |

## 7.3 projectテーブル主要項目

| 列名 | 型 | Null | 説明 |
|---|---|---|---|
| project_id | varchar(26) | No | 主キー |
| owner_user_id | varchar(26) | No | 起案者ID |
| title | varchar(100) | No | タイトル |
| summary | varchar(300) | No | 概要 |
| body | text | No | 本文 |
| target_amount | bigint | No | 目標金額 |
| funding_type | varchar(30) | No | 募集方式 |
| start_at | timestamptz | No | 募集開始日時 |
| end_at | timestamptz | No | 募集終了日時 |
| status | varchar(30) | No | 状態 |
| main_file_id | varchar(26) | Yes | メイン画像 |
| version | bigint | No | 楽観ロック |
| created_at | timestamptz | No | 作成日時 |
| updated_at | timestamptz | No | 更新日時 |

主要索引：`status, start_at, end_at`、`owner_user_id, updated_at desc`。

## 7.4 supportテーブル主要項目

| 列名 | 型 | Null | 説明 |
|---|---|---|---|
| support_id | varchar(26) | No | 主キー |
| project_id | varchar(26) | No | プロジェクトID |
| supporter_user_id | varchar(26) | No | 支援者ID |
| support_amount | bigint | No | 支援金額 |
| status | varchar(30) | No | 支援状態 |
| idempotency_key | varchar(100) | No | 冪等キー |
| payment_id | varchar(26) | Yes | 決済ID |
| version | bigint | No | 楽観ロック |
| created_at | timestamptz | No | 作成日時 |
| updated_at | timestamptz | No | 更新日時 |

一意制約：`supporter_user_id, idempotency_key`。

## 7.5 payment_webhook_eventテーブル

| 列名 | 型 | Null | 説明 |
|---|---|---|---|
| webhook_event_id | varchar(100) | No | 外部イベントID・主キー |
| provider | varchar(30) | No | 決済事業者 |
| event_type | varchar(100) | No | イベント種別 |
| payload_hash | varchar(64) | No | ペイロードハッシュ |
| received_at | timestamptz | No | 受信日時 |
| processed_at | timestamptz | Yes | 処理完了日時 |
| process_status | varchar(30) | No | RECEIVED/PROCESSED/ERROR |
| retry_count | integer | No | 再試行回数 |
| last_error_code | varchar(100) | Yes | 最終エラーコード |

## 7.6 outbox_eventテーブル

| 列名 | 型 | 説明 |
|---|---|---|
| event_id | varchar(26) | 主キー |
| aggregate_type | varchar(100) | 集約種別 |
| aggregate_id | varchar(26) | 集約ID |
| event_type | varchar(200) | イベント型 |
| payload | jsonb | イベント内容 |
| occurred_at | timestamptz | 発生日時 |
| publish_status | varchar(30) | PENDING/PUBLISHED/ERROR |
| retry_count | integer | 再試行回数 |
| next_retry_at | timestamptz | 次回試行日時 |

## 7.7 データ保持

| データ | 保持期間 | 方針 |
|---|---|---|
| プロジェクト・支援 | 教育期間＋1年 | 期間終了後に削除可能 |
| 決済・返金Sandbox | 教育期間＋1年 | 実情報を保持しない |
| 監査ログ | 3年 | 改ざん防止、参照権限限定。保持期限超過分は BAT-009 がS3へアーカイブする |
| AI利用記録 | 1年 | プロンプト本文は必要最小限。同上 |
| 監査アーカイブ（S3） | 上記に加えて1年 | BAT-009 の出力先。専用バケットへ書き込み専用で出力し、`GLACIER_IR` で保管する（ADR-0009） |
| アプリログ | 90日 | CloudWatch Logs |
| メトリクス | 15か月 | 傾向確認用 |
| S3ファイル | プロジェクト削除後90日 | ライフサイクル適用 |

---

# 8. バッチ・非同期処理設計

## 8.1 バッチ一覧

| バッチID | 名称 | 起動 | 概要 |
|---|---|---|---|
| BAT-001 | 公開開始処理 | 1分ごと | 承認済みかつ開始時刻到達の案件を公開する。 |
| BAT-002 | 募集終了処理 | 1分ごと | 終了時刻到達案件を成立・不成立判定する。 |
| BAT-003 | 返金対象作成 | イベント | `ProjectFailed` を購読し、不成立案件の返金要求を作成する。 |
| BAT-004 | 返金実行 | 1分ごと | 返金Sandboxを呼び出す。 |
| BAT-005 | 通知送信 | 1分ごと | メール・画面通知を送る。 |
| BAT-006 | Outbox配送 | 5秒ごと | 未配送イベントをアプリ内Handlerへ配送する（ADR-0008）。 |
| BAT-007 | 決済照合 | 15分ごと | 長時間PENDINGの決済を照会する。 |
| BAT-008 | ファイル清掃 | 日次 | 未完了・期限切れアップロードを削除する。 |
| BAT-009 | 監査アーカイブ | 月次 | 保持期限を超えた監査データをS3へ出力し、検証後にDBから削除する（ADR-0009）。 |
| BAT-010 | 冪等記録削除 | 日次 | 保持期限を超えた冪等記録を削除する。 |

## 8.2 再試行

- 一時障害は指数バックオフで再試行する。
- 業務エラーは自動再試行しない。
- 最大回数超過後はDead Letter QueueまたはERROR状態へ移す。
- OPERATORが原因確認後に再実行できる。
- 再実行操作自体を監査ログへ記録する。

## 8.3 排他

- スケジュールバッチは分散ロックまたはDBロックで多重起動を防止する。
- 1対象の処理は状態条件付きUPDATEまたは楽観ロックで競合を制御する。
- 長時間のテーブルロックを避け、対象を小分けに処理する。

---

# 9. 外部インターフェース設計

## 9.1 Amazon Cognito

- OIDC Authorization Code Flowを利用する。
- PKCEを有効にする。
- Next.js BFFが認証セッションを管理する。
- Cognito Subjectと内部UserIdを分離する。
- ロールはアプリケーションDBを正とし、必要な範囲だけトークンへ反映する。
- **会員の初回登録はJITプロビジョニングで行う**（ADR-0007）。未登録のCognito Subjectが初めて
  APIへ到達した時点で `app_user` を作成し、既定ロール `SUPPORTER` を付与する。昇格は
  API-AD-002（ロール更新）を通す。登録は監査ログ `USER_JIT_PROVISION` に記録する。
- **Resource Serverはアクセストークンのみを受理する**。CognitoはIDトークンとアクセストークンを
  同じissuer・同じJWKSで発行するため、`token_use` と `client_id` を明示的に検証する（ADR-0007）。
  アクセストークンには `email` / `name` が無いため、プロフィールはプレースホルダで作成し
  API-US-002 で本人に更新させる。

## 9.2 Amazon S3

```text
1. WebがアップロードURL発行APIを呼ぶ
2. BackendがファイルIDと署名付きURLを返す
3. BrowserがS3へ直接アップロード
4. Webが完了APIを呼ぶ
5. Backendがサイズ、Content-Type、所有者、S3キーを確認
6. file_objectをAVAILABLEへ更新
```

- バケットは非公開とする。
- オブジェクトキーに元ファイル名・個人情報を含めない。
- 画像はContent-Typeと実体を検査する。
- ダウンロードも署名付きURLを利用する。

## 9.3 Amazon SES

- 通知テンプレートIDとパラメータをNotificationモジュールへ渡す。
- 送信失敗は再試行し、恒久エラーは運用確認対象とする。
- 宛先メールアドレスを通常ログへ出力しない。
- Sandbox環境では許可済み宛先またはMailpitを使用する。

## 9.4 決済Sandbox

| 項目 | 方針 |
|---|---|
| 認証情報 | Secrets Managerで管理 |
| API timeout | 接続3秒、応答10秒を初期値とする |
| 再試行 | POSTの無条件再試行は禁止。外部冪等キー利用時のみ実施 |
| Webhook | 署名、時刻、イベントIDを検証 |
| 確定判定 | Webhookまたは照会APIの結果で確定 |
| ログ | カード番号等を保持しない |

---

# 10. 認証・認可・セキュリティ設計

## 10.1 認証構成

- ブラウザからCognitoへ直接ログインし、認可コードをBFFが受け取る。
- BFFはトークンをサーバー側セッションへ保存する。
- ブラウザCookieはHttpOnly、Secure、SameSite=Lax以上とする。
- BackendはSpring Security Resource Serverとしてトークンを検証する。
- 内部APIはALB、Security Group、認証で多層防御する。

## 10.2 セキュリティ制御

| 脅威 | 制御 |
|---|---|
| XSS | 出力エスケープ、危険HTML除去、CSP |
| CSRF | SameSite Cookie、CSRFトークン、Origin確認 |
| SQL Injection | Prepared Statement、JPA/MyBatisパラメータ |
| SSRF | 接続先許可リスト、内部メタデータIP遮断 |
| 不正ファイル | Content-Type、マジックナンバー、サイズ、拡張子検査 |
| 権限昇格 | API認可、所有権検証、管理者操作監査 |
| ブルートフォース | Cognito制御、レート制限、アラート |
| 秘密情報漏えい | Secrets Manager、ログマスキング、AI入力禁止 |
| 依存脆弱性 | Dependabot/Renovate、SCA、コンテナスキャン |

## 10.3 セキュリティヘッダー

- Content-Security-Policy
- Strict-Transport-Security
- X-Content-Type-Options: nosniff
- Referrer-Policy
- Permissions-Policy
- frame-ancestorsまたはX-Frame-Options

## 10.4 AI利用セキュリティ

- `AGENTS.md`に秘密情報・本番操作禁止を記載する。
- `.env`、秘密鍵、認証ファイルをAI対象外とする。
- AIが生成した依存追加はライセンス・脆弱性を確認する。
- AIには最小権限の実行環境を与える。
- 外部AIへ送信可能なコード範囲は契約・顧客条件に従う。
- プロンプトインジェクションを想定し、リポジトリ内文書の指示を無条件に信頼しない。

---

# 11. エラー・ログ・監視・監査設計

## 11.1 エラー分類

| 分類 | HTTP | 例 | 処理 |
|---|---:|---|---|
| 入力エラー | 400 | 必須、形式、範囲 | 項目エラー返却 |
| 未認証 | 401 | セッション切れ | 再ログイン誘導 |
| 権限不足 | 403 | 他人の編集 | 監査対象 |
| 対象なし | 404 | ID不正 | 情報を過剰開示しない |
| 状態競合 | 409 | 状態遷移不可 | 最新状態を返す |
| 楽観ロック | 409 | 同時更新 | 再読込案内 |
| レート制限 | 429 | 過剰要求 | Retry-After |
| 外部障害 | 502/503 | 決済・SES障害 | 再試行または保留 |
| 内部障害 | 500 | 予期せぬ例外 | 相関ID返却、アラート |

## 11.2 ログ項目

| 項目 | 内容 |
|---|---|
| timestamp | UTC日時 |
| level | INFO/WARN/ERROR |
| service | web/api/worker |
| environment | dev/staging/prod-equivalent |
| correlationId | 要求横断ID |
| traceId/spanId | 分散トレースID |
| userId | 内部ID。必要時のみ |
| operation | ユースケース名 |
| result | SUCCESS/FAILURE |
| errorCode | 定義済みエラーコード |
| durationMs | 処理時間 |

ログへ、パスワード、アクセストークン、Cookie、決済情報、メール本文、AI秘密情報を出力しない。

## 11.3 監査ログ

監査対象：

- ログイン失敗の連続発生
- ロール変更、会員停止
- 審査開始・承認・差戻し・却下
- プロジェクト公開停止・取消
- 支援取消、返金要求、返金再実行
- 設定変更
- AIによる重要コード変更の作成・レビュー・承認

監査ログは追記専用とし、通常業務画面から変更できない。

## 11.4 メトリクス

| 分類 | 主なメトリクス |
|---|---|
| HTTP | リクエスト数、4xx、5xx、p50/p95/p99、同時数 |
| JVM | Heap、GC、Thread、CPU、Virtual Thread滞留 |
| DB | 接続数、遅いSQL、ロック、CPU、ストレージ |
| Queue | メッセージ数、最古メッセージ時間、DLQ数 |
| Business | 審査待ち件数、PENDING決済数、返金失敗数 |
| Deployment | 成功率、ロールバック回数、変更失敗率 |
| AI | AI生成PR数、却下率、修正率、欠陥流出数 |

## 11.5 初期アラート

- 5xx率が5分間で3％超
- API p95が5分間で2秒超
- RDS CPUが10分間で80％超
- DB接続使用率が80％超
- DLQに1件以上
- PENDING決済が30分超
- 返金失敗が1件以上
- 認証失敗が同一送信元から急増

---

# 12. 非機能・インフラ基本設計

## 12.1 性能

| 項目 | 目標 |
|---|---|
| 通常API | p95 500ms以内 |
| 検索API | p95 1秒以内 |
| 画面初期表示 | 主要画面3秒以内を目標 |
| ファイル | 最大10MB、S3直接アップロード |
| 同時利用 | 初期100同時利用者を想定 |
| バッチ | 対象10,000件を許容時間内に分割処理 |

性能値は教育環境の初期目標であり、実案件では負荷条件を再確認する。

## 12.2 可用性・復旧

| 項目 | 初期値 |
|---|---|
| 可用性 | 月間99.5％ |
| RTO | 4時間 |
| RPO | 24時間以内 |
| RDS | Multi-AZはStaging以上で検討 |
| バックアップ | 自動バックアップ、PITR |
| コンテナ | 複数タスク化可能な設計 |
| ロールバック | 直前ECRイメージへ戻せること |

## 12.3 AWS物理構成

```text
VPC
├─ Public Subnet
│  └─ ALB
├─ Private App Subnet
│  ├─ ECS: Next.js
│  ├─ ECS: Spring Boot API
│  └─ ECS: Worker
├─ Private DB Subnet
│  └─ RDS PostgreSQL
└─ VPC Endpoint / NAT
   ├─ S3
   ├─ ECR
   ├─ CloudWatch
   └─ Secrets Manager
```

## 12.4 AWSリソース命名

```text
{system}-{environment}-{component}-{resource}
例：cftraining-dev-api-ecs
```

タグ：`System`、`Environment`、`Owner`、`CostCenter`、`ManagedBy=Terraform`。

## 12.5 コンテナ

- BackendはCorretto 25ベースの最小実行イメージを使用する。
- rootユーザーで実行しない。
- イメージに秘密情報を含めない。
- Health Check、Graceful Shutdownを実装する。
- JVMメモリ上限をコンテナ制約に合わせる。
- SBOMを生成し、ECRスキャンを行う。

## 12.6 データベース接続

- HikariCPを利用する。
- 最大接続数をRDS上限とECSタスク数から算定する。
- Transaction timeoutを設定する。
- SQL実行時間を計測し、遅いSQLを識別する。
- 読み取り大量処理はページング・ストリーミングを使用する。

---

# 13. CI/CD・AI駆動開発設計

## 13.1 ブランチ方針

- `main`：常時デプロイ可能
- `feature/*`：作業ブランチ
- `hotfix/*`：緊急修正
- 長期ブランチを作らず、小さいPull Requestを推奨する。

## 13.2 Pull Request品質ゲート

```text
format
-> compile (Corretto 25 / Node 24)
-> unit test
-> architecture test
-> integration test with Testcontainers
-> frontend test
-> OpenAPI compatibility
-> static analysis
-> dependency/license scan
-> container scan
-> AI review
-> human review
-> merge
```

## 13.3 デプロイフロー

```text
main merge
-> version採番
-> backend/frontend image build
-> SBOM生成
-> ECR push
-> Terraform差分確認
-> Dev自動デプロイ
-> Smoke Test
-> Staging承認デプロイ
-> E2E/受入試験
-> Production相当環境の手動承認
```

## 13.4 AI開発フロー

| 段階 | AIの役割 | 人間の役割 |
|---|---|---|
| 要件確認 | 不明点、矛盾、受入条件案 | 業務判断、回答確定 |
| 設計 | 代替案、ADR草案、影響範囲 | 採否、境界、責任分担の判断 |
| 実装 | コード・試験・文書の草案 | 差分理解、修正、採用判断 |
| レビュー | 欠陥、脆弱性、設計違反の候補 | 真偽判定、修正指示 |
| 障害対応 | ログ分析、原因仮説、修正案 | 原因確定、リリース判断 |

## 13.5 AI作業単位

AIへの依頼は、原則として次を含める。

```text
目的
対象要件ID
対象モジュール
変更可能範囲
変更禁止範囲
受入条件
実行すべきテスト
セキュリティ条件
不明点を推測しない規則
完了時に提示する差分概要
```

## 13.6 AI利用記録

| 項目 | 内容 |
|---|---|
| activity_id | AI作業ID |
| tool | Copilot/Codex/Claude Code等 |
| model | 利用モデル識別 |
| repository | 対象リポジトリ |
| issue_or_pr | Issue/PR番号 |
| purpose | 要件整理、実装、試験、レビュー等 |
| data_classification | PUBLIC/INTERNAL/RESTRICTED |
| result | ACCEPTED/MODIFIED/REJECTED |
| reviewer | 人間レビュー担当 |
| created_at | 実行日時 |

プロンプト全文保存は契約・機密性に応じて判断し、秘密情報を保存しない。

---

# 14. テスト・移行・運用設計

## 14.1 テストレベル

| レベル | 対象 | 主技術 |
|---|---|---|
| Unit | Domain、Value Object、UseCase | JUnit 5、Kotest、Mockito/MockK |
| Architecture | モジュール境界、依存方向 | ArchUnit、Spring Modulith |
| Integration | DB、Flyway、Repository | Testcontainers PostgreSQL |
| API | HTTP、認証、Validation | Spring Boot Test |
| External | S3、SES、決済 | LocalStack、WireMock、Sandbox |
| Frontend | Component、Hook | Vitest |
| E2E | 主要業務フロー | Playwright |
| Performance | 検索、支援、バッチ | k6等 |
| Security | SAST、SCA、DAST | CodeQL、依存検査等 |

## 14.2 主要E2Eシナリオ

1. 起案者が下書きを作成し、審査申請する。
2. 審査担当者が差戻し、起案者が修正して再申請する。
3. 審査担当者が承認し、開始時刻に公開される。
4. 支援者が支援し、Webhookにより決済成功となる。
5. 同一Webhookを再送しても二重計上されない。
6. 募集不成立後、返金要求が作成され、返金完了となる。
7. 権限のない利用者による操作が拒否され、監査記録が残る。
8. AI生成変更がCIと人間レビューを経てマージされる。

## 14.3 初期データ

- ロールマスタ
- 開発用管理者、審査担当者、運用担当者
- サンプル起案者、支援者
- サンプルプロジェクト
- メールテンプレート
- エラーコード・理由区分

パスワード等の認証情報をSQLへ直書きしない。

## 14.4 移行方針

新規システムのため本番データ移行は対象外とする。ただし教育目的で、CSVから会員・プロジェクトを投入する移行演習を実施できる。

移行演習では、件数照合、エラー行隔離、再実行、文字コード、日付、金額、重複IDを確認する。

## 14.5 運用手順

| 運用 | 主担当 | 概要 |
|---|---|---|
| 日次確認 | OPERATOR | アラート、DLQ、PENDING決済、返金失敗 |
| リリース | DEVELOPER/承認者 | 変更確認、デプロイ、Smoke Test |
| 障害一次対応 | OPERATOR | 相関ID、影響、暫定措置 |
| 障害調査 | DEVELOPER | ログ、トレース、DB、外部状態確認 |
| 再処理 | OPERATOR | 原因解消後に画面または管理APIで実行 |
| 権限棚卸し | ADMIN | 四半期ごとに不要権限を削除 |
| 依存更新 | DEVELOPER | 月次または緊急時 |
| バックアップ復旧訓練 | ADMIN/DEVELOPER | 半期ごと |

## 14.6 障害対応票の必須項目

- 発生日時、検知経路
- 影響範囲、対象件数
- 相関ID、Trace ID
- 直前変更
- 暫定措置
- 原因
- 恒久対策
- 再発防止試験
- AIを利用した場合の提案内容と人間の判断

---

# 15. トレーサビリティと詳細設計引継ぎ

## 15.1 要件・設計対応

| 要件ID | 基本設計 | 主な成果物 |
|---|---|---|
| FR-01/02 | 2、5、6、10章 | 画面、認証API、権限マトリクス |
| FR-03/04 | 3、4、5、6、7章 | Project/Reviewモデル、画面、API、DB |
| FR-05/06/07 | 3、6、7、8、9章 | 支援、決済、返金、Webhook、バッチ |
| FR-08/09 | 8、9章 | S3、SES、通知キュー |
| FR-10/11 | 5、6、7、11章 | 管理画面、監査API、監査テーブル |
| FR-12/13 | 8、11、14章 | バッチ、監視、運用手順 |
| AI要件 | 10、13、14章 | AI統制、ログ、品質ゲート |
| 非機能要件 | 10～14章 | 性能、可用性、セキュリティ、運用 |

## 15.2 詳細設計で作成するもの

- 各画面の項目定義、レイアウト、イベント、Validation
- 各APIのRequest/Response Schema、エラーコード、シーケンス
- 各テーブルの全列、制約、索引、DDL
- 各集約のクラス図、Invariant、メソッド、イベント
- 各バッチの処理フロー、SQL、分割単位、再試行条件
- AWSリソースパラメータ、Security Group、IAM Policy
- ログ出力ポイント、メトリクス名、アラート閾値
- AI用AGENTS.md、標準プロンプト、許可コマンド
- テストケース、テストデータ、受入判定表

## 15.3 基本設計完了条件

1. 主要業務フローと状態遷移が合意されている。
2. 画面、API、テーブルに一意のIDが付いている。
3. コンテキストとモジュールの責務が重複していない。
4. 認証・認可・監査の責務が定義されている。
5. 決済・返金・Webhookの冪等性が定義されている。
6. バッチ・非同期処理の再試行と運用方法が定義されている。
7. 非機能要件がAWS構成と監視へ反映されている。
8. AIに許可する作業と禁止する作業が明確である。
9. 詳細設計へ引き渡す未確定事項が識別されている。
10. 開発、運用、教育の関係者がレビューしている。

---

# 付録A. エラーコード体系

```text
CF-{MODULE}-{CATEGORY}-{NUMBER}
```

例：

| コード | 内容 |
|---|---|
| CF-PROJECT-STATE-001 | 現在状態では審査申請できない |
| CF-REVIEW-AUTH-001 | 審査権限がない |
| CF-FUNDING-LIMIT-001 | リターン数量上限を超えた |
| CF-PAYMENT-EXTERNAL-001 | 決済事業者への接続に失敗した |
| CF-PAYMENT-WEBHOOK-001 | Webhook署名検証に失敗した |
| CF-REFUND-STATE-001 | 返金対象状態ではない |
| CF-FILE-TYPE-001 | 許可されていないファイル形式 |
| CF-COMMON-CONFLICT-001 | 同時更新を検出した |

# 付録B. API命名規則

- リソース名は複数形の名詞を使用する。
- 動作が状態遷移である場合は、末尾に業務動詞を使用する。
- URLへ画面名、DB名、技術名を含めない。
- IDは内部型付きIDを外部表現へ変換する。
- 検索条件が複雑な場合でも、初期はGETクエリを基本とし、機密条件をURLへ載せない。

# 付録C. Definition of Done

- 要件IDと受入条件が明記されている。
- Corretto 25でビルド・実行できる。
- KotlinとJavaのJVM targetが25で一致している。
- DDD境界と依存方向に違反していない。
- 単体、結合、必要なE2E試験が追加されている。
- OpenAPI、DB Migration、運用文書が更新されている。
- 静的解析、脆弱性、ライセンス検査を通過している。
- AI生成箇所を人間が理解し、レビューしている。
- 秘密情報・個人情報をログ・AIへ送信していない。
- ロールバックと障害時の確認方法が定義されている。

---

# English Summary

This basic design document converts the previously defined requirements and technology choices into an implementation-ready system design.

The system is a training-oriented crowdfunding web application built with Amazon Corretto 25, Kotlin and Java, Spring Boot 4.1, Domain-Driven Design, React 19.2, Next.js 16.2, PostgreSQL 18, Docker, AWS, Terraform, and GitHub Actions.

The backend is designed as a modular monolith. Business boundaries are separated into Identity, Project, Review, Funding, Payment, File, Notification, and Audit contexts. Modules communicate through application APIs and domain events rather than direct cross-module database access.

The document defines user roles, business workflows, project and payment state transitions, screens, APIs, database tables, batch jobs, external integrations, authentication, authorization, security controls, logging, monitoring, auditing, deployment, testing, and operations.

AI tools may support requirements analysis, design, coding, testing, review, documentation, and incident analysis. Humans must approve requirements, architecture decisions, code changes, merges, and deployments. AI agents are prohibited from accessing production secrets, customer data, production databases, and unrestricted deployment permissions.
