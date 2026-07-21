# CF-Training 残タスク一覧

- 対象リポジトリ: `F:\11\CF`（GitHub: `https://github.com/rice-3/cf.git`）
- 上位文書: 基本設計 BD-CF-001 v1.2 / 詳細設計 DD-CF-001 v1.2（`G:\マイドライブ\CF\`）
- 更新日: 2026-07-21（残タスク再整理）
- 実装済み範囲の詳細は `ses_ai_ddd_implementation_status.md` を参照。
- 本書は**残タスク**を主役とする。完了済みは §5 に要約のみ記載。

---

## 1. サマリ

| # | 工程（詳細設計 §16.2） | 状態 |
|---|---|---|
| 1〜9 | Shared Kernel 〜 Identity/Admin/Audit（バックエンド全機能） | ✅ 完了 |
| — | フロントエンド 全19画面（基本設計 §5.2） | ✅ 完了 |
| 10 | 監視・CI/CD・E2E・IaC・運用手順 | ⬜ **未着手（残タスクの中心）** |

- バックエンドの業務API（API-PJ/RV/FL/FD/PY/RF/US/AD/AU）は全系列実装済み。
- 業務フローは「起案 → 審査 → 公開 → 支援 → 決済 → 募集終了 → 返金 → 通知」まで一気通貫で動作。
- **残るのは主に工程10（運用基盤）** と、少数のバックエンド追加API・要判断事項。

---

## 2. 残タスク（優先度: 高）

### 2.1 CI/CD

- [x] **`.github/workflows/` のCI構築** — `ci.yml` / `codeql.yml` を作成（詳細設計 §13.4）。
  - `ci.yml`: backend（Wrapper検証 → Corretto 25 → `gradlew build` = compile/unit/ArchUnit/
    Testcontainers統合）、frontend（Node 24 → typecheck → build）、secret-scan（gitleaks）
  - `codeql.yml`: SAST（**javascript-typescript**、フロントエンド）※パブリックリポジトリまたはGHASが必要
  - `semgrep.yml`: **JVM側SAST（Kotlin/Java）** をSemgrepで実施。結果はCode Scanningへレポート
- [x] **JVM側SAST（Kotlin/Java）の有効化** — CodeQLのKotlin抽出器がKotlin 2.4系に未対応
  （`CODEQL_EXTRACTOR_KOTLIN_ALLOW_UNSUPPORTED_VERSION=true` でも抽出不可を確認）だったため、
  **Semgrep**（`p/java` + `p/kotlin`）で代替。Semgrepはソース直接解析でコンパイル/JDK/Kotlin
  バージョンに非依存。検出はCode Scanning（Securityタブ）へSARIF報告（現状はレポート方式で非ブロッキング）。
  - [ ] CodeQLがKotlin 2.4対応後に `codeql.yml` へ java-kotlin を追加（Semgrepと併用 or 置換を判断）
  - [ ] Semgrepをゲート化（`continue-on-error`除去 or `semgrep ci`）する場合は既存findingsの棚卸しが前提
- [x] **format** — Spotless + ktlint 1.5.0 導入（`com.diffplug.spotless`）。`spotlessCheck` は
  `check`→`build` に自動組込みでCIの `gradlew build` で検証。全ソースを `spotlessApply` 済み。
  ktlintは `intellij_idea` スタイル。設計上の正当な命名のため `package-name`（`adapter.in.*`）/
  `filename`（複数宣言まとめ）/ `max-line-length`（日本語コメント）を無効化。
  - [ ] Java整形は未対応（google/palantir-java-format が JDK 25 で不安定なため）。JDK 25対応後に追加。
- [x] **依存/ライセンスscan** — `security-scan.yml` の `dependency-license` ジョブ（Trivy fs）。
  脆弱性はSARIFでCode Scanningへ、ライセンスはログにテーブル出力（いずれも非ブロッキング）。
- [x] **コンテナscan** — `backend/Dockerfile`（マルチステージ、Corretto 25、非root）を追加し、
  `security-scan.yml` の `container` ジョブでイメージをビルド→Trivy image スキャン→SARIF報告。
- [x] **OpenAPI互換チェック** — springdoc 3.0.3（Boot 4対応）でコードからspec生成し、
  `docs/api/openapi.yaml` へコミット。生成は決定論的（`OpenApiConfig` でinfo/servers固定、
  `springdoc.writer-with-order-by-keys=true` でキーソート）。
  - 鮮度ゲート: `OpenApiSpecIntegrationTest`（`gradlew build`）が実仕様とコミットspecの一致を検証。
    API変更時にspec未更新なら失敗する（更新手順はテストのKDoc参照）。
  - 互換ゲート: `openapi.yml` が oasdiff で base→現在のspecを比較し、破壊的変更（`--fail-on ERR`）で失敗。
  - `/v3/api-docs.yaml` を公開（SecurityConfigで許可）。本番で隠す場合は `springdoc.api-docs.enabled=false`。
  - [ ] contract-first のDTO自動生成（§6.15）は未対応（現状は code-first + spec生成）。UI（swagger-ui）も未導入。
- [x] **スキャンのゲート化** — findings棚卸し（Code Scanning: Semgrep 0件 / Trivy high19・medium3、
  うち high はすべてベースイメージ由来、fs依存とjacksonは medium）を実施のうえ、緑を保ちつつゲート化:
  - Semgrep: `--error` でfindingsがあれば失敗（現状0件で緑）。
  - Trivy fs（依存）: `--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1` で、我々が更新で直せる
    高深刻度のみブロック（現状0件で緑。ローカルTrivyでもexit 0確認）。
  - Trivy コンテナ: ベースイメージ（AL2023）のOS脆弱性はCVE公開で増減するためレポート方式を継続。
    是正はベースイメージ更新で行い、Code Scanningで追跡。
- [x] **CD** — `cd.yml`（手動 `workflow_dispatch`、環境承認付き）。image build → ECR push →
  ECS ローリング更新（`aws-actions/*`, OIDC）。前提の **Terraform（§3.3）を提供済み**（下記）。
  残作業は「実AWSへ `terraform apply` → `terraform output` をGitHub Variablesへ設定」のみ。
  AWS未提供のため実行（apply/deploy）検証は未実施。

### 2.2 運用者向け検索API + SCR-060/061 の一覧UI化

- [ ] **運用者向け 支援検索 / 返金検索 API**
  - 現状 `/api/v1/operations/**` はアクション系（返金要求・再実行・決済照合）のみで、
    OPERATORが支援・返金を横断検索する read API が無い。
  - このため SCR-060/061 は暫定で「ID指定アクションコンソール」実装（§5.3 参照）。
  - API追加後、`operations` 画面を一覧・検索UIへ拡張する。

---

## 3. 残タスク（優先度: 中）

### 3.1 監視・アラート（詳細設計 §12.5–12.6、§9.3）

- [ ] メトリクス公開（Micrometer/OpenTelemetry）とアラート閾値設定
  - `outbox_pending_count` / `oldest_outbox_age` / `notification_failure_rate` /
    `refund_retry_count` / `batch_last_success_age` / API 5xx率 / p95 レイテンシ 等。

### 3.2 E2Eテスト

- [ ] Playwrightで主要ユーザーストーリー（起案→審査→公開→支援→返金）を自動化（詳細設計 §14.4）。

### 3.3 IaC（Terraform、ADR-007）

- [x] **コアAWS構成のTerraform化** — `infra/terraform/`（VPC / サブネット / NAT / SG / ECR /
      ALB / ECS Fargate / RDS PostgreSQL 18 / IAM(タスク実行・タスク) / GitHub OIDC + デプロイロール /
      Secrets Manager / CloudWatch Logs）。CI（`terraform.yml`）で fmt / init / validate 済み。
      `apply` はAWS認証が必要なため手動運用（`infra/terraform/README.md`）。CDの前提を満たす。
- [ ] **未カバーのAWSリソース** — HTTPS(ACM) / S3ファイルバケット / SQS / SES ドメイン検証 /
      Cognito User Pool / WAF / VPCエンドポイント。アプリDBユーザーのプロビジョニング。
- [ ] **SESテンプレートのAWS登録** — 本文は `NotificationTemplateCatalog` を正として定義済み。
      `aws_sesv2_email_template` またはCLIで実登録する（テンプレートIDはカタログのキー）。

### 3.4 運用手順書

- [ ] バッチ再実行、返金の手動対応、決済照合、障害時の切り分け（相関ID/Trace追跡）手順。

---

## 4. 未確定・要判断事項（人間の決定待ち）

| # | 内容 | 影響・対応 |
|---|---|---|
| A | 監査アーカイブ（BAT-009）の実出力先（S3バケット・ストレージクラス・保持年数） | 現状はハッシュ算出のみのローカル実装。`LocalAuditArchiveAdapter` に `TODO(question)`。§3.3 Terraformと併せて確定 |
| B | Outbox配送のSQS切替（現状 `InProcessOutboxDispatcher` のアプリ内配送） | マルチインスタンス構成時に必要。ADR候補（§3.3と関連） |
| C | 未登録Cognito Subjectの初回JIT自動登録（既定ロールSUPPORTER）の可否 | `CognitoJwtAuthenticationConverter` に `TODO(question)`。許容しない場合は管理者Invite方式へ変更。dev投入前に承認要 |
| D | ADR-BFF配置 / 決済非同期UI / Rich Text形式 の3件が未起票 | 該当機能の本格化時に起票（現状は既定動作で実装済み） |
| E | Cognito実User Poolでの結合確認 | 未実施（テストはlocal/testの開発用ヘッダー認証のみ）。dev環境構築時に実施 |

> 解決済みの要判断: 起案者向け通知の宛先解決（ADR-0002）、冪等記録削除バッチ（BAT-010）、
> バッチ多重起動防止（ADR-0003: ShedLock）。詳細は §5。

---

## 5. 完了済みの要約（詳細は `ses_ai_ddd_implementation_status.md`）

### 5.1 バックエンド（工程1〜9）

- DDD/ヘキサゴナル/モジュラーモノリス。Project / Review / Funding / Payment / Notification /
  File / Identity / Audit の各コンテキスト。ArchUnitで境界を強制。
- API全系列、RFC 9457 Problem Details、Transactional Outbox、冪等制御、楽観ロック。
- バッチ BAT-001〜010（公開/募集終了/返金対象作成/返金実行/通知/Outbox配送/決済照合/
  ファイル清掃/監査アーカイブ/冪等記録削除）。
- 工程8の残タスク3件を完了:
  - 起案者向け通知の宛先解決（**ADR-0002**: イベントに`ownerUserId`追加、起案者向けテンプレート6種購読）
  - SESテンプレート本文を `NotificationTemplateCatalog` に一元定義（Mockがレンダリング、SES登録はカタログを正）
  - 冪等記録削除バッチ **BAT-010**
- バッチ多重起動防止（**ADR-0003**: ShedLock。BAT-006 Outboxは競合コンシューマ設計のため除外）。
- 工程9: API-US/AD/AU、Cognito JWT変換（`CognitoJwtAuthenticationConverter`）。

### 5.2 フロントエンド（Next.js 16、全19画面）

| 区分 | 画面 |
|---|---|
| 公開 | SCR-010 検索 / SCR-011 詳細 |
| OWNER | SCR-020 一覧 / SCR-021 編集 / SCR-022 プレビュー / SCR-023 審査申請確認 |
| REVIEWER | SCR-030 審査一覧 / SCR-031 審査詳細 |
| SUPPORTER | SCR-040 支援入力 / SCR-041 確認 / SCR-042 結果 / SCR-051 支援履歴 |
| OPERATOR | SCR-060 支援管理 / SCR-061 返金管理 |
| ADMIN | SCR-070 会員・ロール管理 / SCR-071 監査ログ検索 |
| 共通 | SCR-001 ログイン / SCR-002 アクセス拒否 / SCR-050 マイページ / SCR-080 システムエラー |

- 開発用ログイン（SCR-001）は HttpOnly Cookie でロール切替。BFFが `X-Dev-User`/`X-Dev-Roles` へ変換
  （§7.9。本番はCognito OIDCへ置換）。
- 型/定数は `lib/api-types.ts`、`next/headers`依存の `backendFetch` は `lib/backend.ts`（server-only）に分離。
- 実機（Docker+backend+frontend）で全ロール画面の表示・認可・主要フローを確認済み。

### 5.3 既知の暫定実装（要フォロー）

- **SCR-060/061（OPERATOR）**: 運用者向け検索APIが無いため、ID指定のアクションコンソールとして実装。
  一覧・検索UI化は §2.2 の API追加が前提。
- **メイン画像アップロード**: local/testはS3スタブ（発行時点で完了扱い）のため実PUTを行わない。
  dev以上の実S3接続時はブラウザからの直接PUT追加が必要。

### 5.4 その他

- [x] git初回コミット・push済み（`main`）。
- [ ] 設計書 `.docx` の再出力（`.md` は両書 v1.2、`.docx` は v1.0 のまま）。

---

## 6. 実装時の注意点（既知の落とし穴）

同種の実装を追加する際の参考。

| 項目 | 内容 |
|---|---|
| `@Transactional` 自己呼出し | `REQUIRES_NEW` は同一クラス内呼出しではプロキシを経由せず**無効**。外部呼出しを挟む処理は別Beanへ切り出す（`PaymentTransactionSteps` / `NotificationTransactionSteps`） |
| `readOnly` トランザクション | `SELECT ... FOR UPDATE SKIP LOCKED` は読み取り専用Txで**実行不可**（PostgreSQLがエラー） |
| テストのHTTPクライアント | `RestTemplate()` 既定（HttpURLConnection）は**401応答の本文を破棄**。エラーコード検証は `RestTemplate(JdkClientHttpRequestFactory())` |
| テスト時のスケジューラ | スケジューリングはtestプロファイルで無効（`SchedulingConfig` は `@Profile("!test")`）。バッチ検証はUseCase/バッチ処理を直接呼ぶ |
| Hibernateスキーマ検証 | `ddl-auto: validate` のため Migration型とEntityマッピングの不一致は起動失敗（`char(n)`は`bpchar`扱い）。`shedlock`等の非Entityテーブルは対象外 |
| JPQLのnullパラメータ | `LIKE`/`concat` にnullを渡すと型推論が`bytea`になり `character varying ~~ bytea` エラー。呼出し側で空文字へ正規化する（SCR-010で実際に踏んだ） |
| クライアント/サーバー境界 | Client Component（"use client"）から `next/headers` 依存モジュールをimportするとビルド失敗。型/定数は `lib/api-types.ts` に分離する |
