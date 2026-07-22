# CF-Training 残タスク一覧

- 対象リポジトリ: `F:\11\CF`（GitHub: `https://github.com/rice-3/cf.git`）
- 上位文書: 基本設計 BD-CF-001 v1.2 / 詳細設計 DD-CF-001 v1.2（`G:\マイドライブ\CF\`）
- 更新日: 2026-07-22（残タスク再整理: 完了済みを §5 へ集約）
- 実装済み範囲の詳細は `ses_ai_ddd_implementation_status.md` を参照。
- 本書は**残タスク**を主役とする。完了済みは §5 に要約のみ記載。

---

## 1. サマリ

| # | 工程（詳細設計 §16.2） | 状態 |
|---|---|---|
| 1〜9 | Shared Kernel 〜 Identity/Admin/Audit（バックエンド全機能） | ✅ 完了 |
| — | フロントエンド 全19画面（基本設計 §5.2） | ✅ 完了 |
| 10 | CI/CD・スキャン・IaC（コア）・メトリクス公開・E2E・運用手順書 | ✅ 完了 |
| 10 | 監視アラート実配線・IaC（残リソース）・SES登録 | ⬜ **残タスクの中心（主にAWS依存）** |

- バックエンドの業務API（API-PJ/RV/FL/FD/PY/RF/US/AD/AU）は全系列実装済み。
- 業務フローは「起案 → 審査 → 公開 → 支援 → 決済 → 募集終了 → 返金 → 通知」まで一気通貫で動作。
- CI/CD 一式（ビルド/テスト/SAST/依存・コンテナscan/OpenAPI互換/Terraform検証/CD雛形）は構築・ゲート化済み。
- 監視メトリクス（Micrometer → `/actuator/prometheus`、ビジネス滞留/バッチ稼働/APIレイテンシ）と
  アラート閾値定義（`docs/ops/monitoring.md`）は完了。残るは監視基盤への実配線（§2.1）。
- E2E（Playwright）で「起案→審査承認」ジャーニー・ロール別アクセス制御・運用コンソールを検証（`e2e.yml`）。
- 運用手順書（`docs/ops/runbook.md`）を整備。
- **残るのはほぼAWS依存の運用基盤（監視アラートの実配線・IaCの残リソース・SES登録）** と、少数の要判断事項・軽微なフォローアップ。

### 残タスク早見表

| 優先 | 区分 | タスク | 節 |
|---|---|---|---|
| 高 | IaC | 未カバーAWSリソース（ACM/S3/SQS/SES/Cognito/WAF/VPCe/DBユーザー） | 2.1 |
| 高 | 監視 | アラート閾値のCloudWatch/Alertmanager実配線（メトリクス公開は完了） | 2.1 |
| 中 | 運用 | SESテンプレートのAWS実登録 | 3.1 |
| 低 | CI | CodeQL Kotlin対応後の java-kotlin 追加検討 | 4.1 |
| 低 | CI | contract-first DTO自動生成 / swagger-ui 導入 | 4.1 |
| 低 | CI | Java整形（google/palantir-java-format、JDK25対応後） | 4.1 |
| 低 | 文書 | 設計書 `.docx` の v1.2 再出力 | 4.1 |
| — | 判断 | 要判断事項 A〜E（人間の決定待ち） | 5 の後 §6 |

---

## 2. 残タスク（優先度: 高）

### 2.1 IaC — 未カバーのAWSリソースと監視の実配線（Terraform、ADR-007）

- [ ] **未カバーのAWSリソース** — HTTPS(ACM) / S3ファイルバケット / SQS / SES ドメイン検証 /
      Cognito User Pool / WAF / VPCエンドポイント。アプリDBユーザーのプロビジョニング。
  - コア構成（VPC/ECR/ECS/ALB/RDS/IAM(OIDC)/Secrets/Logs）は §5.3 で提供済み。本項はその上乗せ。
  - 完了すると CD（`cd.yml`）が実AWSに対して機能する（残るは `apply` → `output` を GitHub Variables へ設定）。
- [ ] **アラート閾値の実配線** — メトリクス公開とアラート閾値定義は完了（§5.3 / `docs/ops/monitoring.md`）。
      CloudWatch Agent/OTel Collector で `/actuator/prometheus` を収集し、CloudWatch Alarm（or Alertmanager）へ
      閾値を実設定する。ダッシュボード（CloudWatch/Grafana）も本項で作成。監視基盤のIaC化と併せて実施。

---

## 3. 残タスク（優先度: 中）

### 3.1 SESテンプレートのAWS登録

- [ ] 本文は `NotificationTemplateCatalog` を正として定義済み。`aws_sesv2_email_template` またはCLIで
      実登録する（テンプレートIDはカタログのキー）。§2.1 の SES ドメイン検証と併せて実施。

---

## 4. 残タスク（優先度: 低 / フォローアップ）

### 4.1 CI・文書の軽微なフォローアップ

- [ ] **CodeQL Kotlin対応** — CodeQLがKotlin 2.4対応後、`codeql.yml` へ java-kotlin を追加
      （現状Semgrepで代替中。Semgrepと併用 or 置換を判断）。※上流（CodeQL）待ち。
- [x] **contract-first DTO自動生成 / swagger-ui**（§6.15）— 完了（§5.3）。
      swagger-ui（`/swagger-ui.html`）導入、フロント型を spec から生成（`npm run gen:api-types`）+ CI鮮度ゲート。
- [x] **Java整形** — 完了（§5.3）。google/palantir は JDK 25 で javac 内部API非互換のため、
      JDK非依存の Eclipse JDT フォーマッタを Spotless に採用（コメントは保全しコードのみ整形）。
- [x] **設計書 `.docx` の再出力** — 完了。`G:\マイドライブ\CF` の md から pandoc で再生成
      （原本の書式を `--reference-doc` で継承、旧版は `*.v1.0.docx` として退避）。
      なお md の版数表は両書とも「1.0」で、当初の「v1.2」記載は事実誤りだったため docx と内容を同期した。

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

### 5.3 CI/CD・スキャン・IaC（工程10、構築済み）

- **CI** — `ci.yml`: backend（Wrapper検証 → Corretto 25 → `gradlew build` = compile/unit/ArchUnit/
  Testcontainers統合）、frontend（Node 24 → typecheck → build）、secret-scan（gitleaks）。
- **format** — Spotless + ktlint 1.5.0（`spotlessCheck` を `build` に自動組込み）。
- **SAST** — `codeql.yml`（JS/TS）+ `semgrep.yml`（JVM側 Kotlin/Java。CodeQLがKotlin 2.4未対応のため代替）。
- **依存/ライセンス/コンテナscan** — `security-scan.yml`（Trivy fs + image。`backend/Dockerfile` はマルチ
  ステージ/Corretto 25/非root）。ゲート化済み（Semgrep `--error` / Trivy fs は修正可能なHIGH,CRITICALで失敗、
  コンテナは非ブロッキング・レポート方式）。
- **OpenAPI** — springdoc 3.0.3 でコードから spec 生成 → `docs/api/openapi.yaml` にコミット。鮮度ゲート
  （`OpenApiSpecIntegrationTest`）+ 互換ゲート（`openapi.yml` の oasdiff、破壊的変更で失敗）。
  **Swagger UI**（`springdoc-openapi-starter-webmvc-ui`）を `/swagger-ui.html` で提供（本番は
  `springdoc.swagger-ui.enabled=false` で無効化可）。**contract-first 型生成**: `openapi-typescript` で
  spec からフロント型を生成（`npm run gen:api-types` → `frontend/src/lib/generated/api.ts`）、
  CIで再生成差分ゲート（`ci.yml`）。
- **コード整形（Java）** — Spotless に Eclipse JDT フォーマッタ（`spotless-java.properties`）を追加。
  google/palantir は JDK 25 で javac 内部API非互換のため不採用。コメントは保全しコードのみ整形。
- **IaC（コア）** — `infra/terraform/`（VPC / サブネット / NAT / SG / ECR / ALB / ECS Fargate /
  RDS PostgreSQL 18 / IAM(タスク実行・タスク) / GitHub OIDC + デプロイロール / Secrets Manager /
  CloudWatch Logs）。`terraform.yml` で fmt / init / validate。`apply` は手動運用（`infra/terraform/README.md`）。
- **CD** — `cd.yml`（手動 `workflow_dispatch`、環境承認付き）。image build → ECR push → ECS ローリング更新
  （OIDC）。実AWS未提供のため apply/deploy 検証は未実施（残: §2.1 のリソース整備後）。
- **監視メトリクス** — Micrometer + `/actuator/prometheus`（`micrometer-registry-prometheus`）。
  ビジネス滞留（`BusinessMetrics`: outbox/notification/refund の未処理・失敗ゲージ）、
  バッチ稼働（`BatchMetrics`: `cf_batch_last_success_age_seconds` / `cf_batch_runs_total`）、
  通知送信レート（`cf_notification_delivery_total`）、API レイテンシ/5xx（`http.server.requests` ヒストグラム）。
  全メーターに `application` 共通タグ（`ObservabilityConfig`）。アラート閾値は `docs/ops/monitoring.md`。
  結合テスト `MetricsIntegrationTest` で公開を検証。
- **E2E（Playwright）** — `frontend/e2e/`（`playwright.config.ts`）。ブラウザで「起案→審査承認」ジャーニー、
  ロール別アクセス制御、公開画面、運用コンソール一覧・検索を検証。`e2e.yml` で PostgreSQL(サービス) +
  backend(local, `java -jar`) + frontend(`next start`) を起動して実行。ローカルは
  `docker compose up -d postgres` + `bootRun` + `npm run test:e2e`。公開→支援→決済→返金は
  バッチ・Webhook依存のためバックエンド結合テストで網羅（E2Eは画面到達可能な状態遷移を対象）。
- **運用手順書** — `docs/ops/runbook.md`。バッチ運用（再実行方針）、決済照合、返金の手動対応、
  Outbox滞留・通知失敗、障害切り分け（相関ID/監査ログ/メトリクス）、アラート→一次対応表、
  デプロイ/ロールバックを記載。本番手動変更は監査対象（要件C-17）である旨を明記。

### 5.4 既知の暫定実装（要フォロー）

- **SCR-060/061（OPERATOR）**: 運用者向け検索API（支援検索/返金検索）を追加し、一覧・検索UIへ移行済み。
- **メイン画像アップロード**: local/testはS3スタブ（発行時点で完了扱い）のため実PUTを行わない。
  dev以上の実S3接続時はブラウザからの直接PUT追加が必要。

### 5.5 その他

- [x] git初回コミット・push済み（`main`）。CI 全ワークフロー緑を維持。

---

## 6. 未確定・要判断事項（人間の決定待ち）

| # | 内容 | 影響・対応 |
|---|---|---|
| A | 監査アーカイブ（BAT-009）の実出力先（S3バケット・ストレージクラス・保持年数） | 現状はハッシュ算出のみのローカル実装。`LocalAuditArchiveAdapter` に `TODO(question)`。§2.1 Terraformと併せて確定 |
| B | Outbox配送のSQS切替（現状 `InProcessOutboxDispatcher` のアプリ内配送） | マルチインスタンス構成時に必要。ADR候補（§2.1と関連） |
| C | 未登録Cognito Subjectの初回JIT自動登録（既定ロールSUPPORTER）の可否 | `CognitoJwtAuthenticationConverter` に `TODO(question)`。許容しない場合は管理者Invite方式へ変更。dev投入前に承認要 |
| D | ADR-BFF配置 / 決済非同期UI / Rich Text形式 の3件が未起票 | 該当機能の本格化時に起票（現状は既定動作で実装済み） |
| E | Cognito実User Poolでの結合確認 | 未実施（テストはlocal/testの開発用ヘッダー認証のみ）。dev環境構築時に実施 |

> 解決済みの要判断: 起案者向け通知の宛先解決（ADR-0002）、冪等記録削除バッチ（BAT-010）、
> バッチ多重起動防止（ADR-0003: ShedLock）。詳細は §5。

---

## 7. 実装時の注意点（既知の落とし穴）

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
