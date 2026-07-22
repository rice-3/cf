# CF-Training — クラウドファンディング型教育・実践開発システム

SES向け教育・実践開発プロジェクト。クラウドファンディング業務を題材に、
DDD・モジュラーモノリス・AI駆動開発を実践する教育用システムです。

- 要件定義・技術選定: `docs/requirements/`（上位文書参照）
- 基本設計: BD-CF-001 / 詳細設計: DD-CF-001

## 技術スタック

| 区分 | 技術 |
|---|---|
| JDK | Amazon Corretto 25（Gradle Toolchainで自動取得） |
| Backend | Kotlin（主）+ Java（副）/ Spring Boot 4.1 / Spring Framework 7 |
| Build | Gradle 9.6（Wrapper） |
| DB | PostgreSQL 18 / Flyway |
| Frontend | TypeScript / React 19 / Next.js 16（App Router） |
| Test | JUnit 5 / Kotest / MockK / Testcontainers / ArchUnit |
| Infra | Docker / AWS（ECS Fargate, RDS, S3, SQS, SES, Cognito） |

## リポジトリ構成

```text
backend/    Spring Boot モジュラーモノリス（パッケージ分割、ADR-001）
frontend/   Next.js 16 Web/BFF
docs/       設計文書・ADR・API仕様
infra/      docker-compose 等
.github/    CI ワークフロー
AGENTS.md   AIコーディングエージェント向け規約
```

## ローカル起動

```bash
# 1. DB起動
docker compose -f infra/docker-compose.yml up -d

# 2. Backend（初回はCorretto 25が自動ダウンロードされる）
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. Frontend
cd frontend
npm install
npm run dev
```

- API: http://localhost:8080/api/v1
- Web: http://localhost:3000

### local プロファイルの認証

教育用の `local` プロファイルでは Cognito の代わりに開発用ヘッダー認証を使用します
（`X-Dev-User: usr_...` / `X-Dev-Roles: OWNER,REVIEWER`）。
`dev` 以上の環境では OIDC（Cognito）+ Spring Security Resource Server に切り替えます。

## テスト

```bash
cd backend
./gradlew test          # 単体 + ArchUnit
./gradlew check         # 統合テスト含む（Docker必須: Testcontainers PostgreSQL）
```

### E2E（Playwright）

ブラウザで主要ジャーニー（起案→審査承認）・ロール別アクセス制御・運用コンソールを検証します。

```bash
# 1. DB + backend(local) を起動
docker compose -f infra/docker-compose.yml up -d postgres
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'   # 別ターミナル

# 2. E2E実行（frontend を build して next start を自動起動）
cd frontend
npm run build
npm run test:e2e
```

## CI

GitHub Actions（`.github/workflows/`）でPR品質ゲートを実行（詳細設計 §13.4）。

- `ci.yml` — backend（format/Spotless → compile → unit → ArchUnit → integration/Testcontainers）、
  frontend（typecheck → build）、secret-scan（gitleaks）
- `codeql.yml` — SAST（javascript-typescript、フロントエンド）
- `semgrep.yml` — JVM側SAST（Kotlin/Java、Semgrep）。CodeQLがKotlin 2.4未対応のためSemgrepで実施
- `security-scan.yml` — Trivy（依存/ライセンス fs スキャン + コンテナイメージスキャン）
- `openapi.yml` — OpenAPI破壊的変更チェック（oasdiff）
- `cd.yml` — ECSデプロイ（手動 `workflow_dispatch`）。`infra/terraform` apply + Variables設定後に有効化
- `terraform.yml` — IaC検証（fmt / init / validate）
- `e2e.yml` — ブラウザE2E（Playwright）。PostgreSQL + backend(local) + frontend を起動し主要ジャーニーを検証

IaC は `infra/terraform/`（VPC/ECR/ECS Fargate/ALB/RDS/IAM(OIDC)/Secrets/CloudWatch、基本設計 §12.3）。
詳細は `infra/terraform/README.md`。

スキャンのゲート: Semgrep は findings で失敗、Trivy fs は修正可能な HIGH/CRITICAL で失敗。
コンテナ（ベースイメージ）はレポート方式。

OpenAPI仕様は springdoc で生成し `docs/api/openapi.yaml` にコミット。`OpenApiSpecIntegrationTest` が
コードとspecの一致を検証、`/v3/api-docs.yaml` で参照可能。

コード整形は Spotless + ktlint（`./gradlew spotlessApply` で整形、`spotlessCheck` は `build` に自動組込み）。
コンテナは `backend/Dockerfile`（マルチステージ / Corretto 25 / 非root）。

監視は Micrometer で `/actuator/prometheus` にメトリクスを公開（ビジネス滞留・バッチ稼働・APIレイテンシ）。
メトリクスカタログとアラート閾値は `docs/ops/monitoring.md`、運用手順（バッチ再実行・返金・照合・障害切り分け）は
`docs/ops/runbook.md`。

## 実装状況

バックエンド全機能（工程1〜9）とフロントエンド全19画面（基本設計 §5.2）が完了。

- [x] 工程1〜9: Shared Kernel 〜 Identity/Admin/Audit（API-PJ/RV/FL/FD/PY/RF/US/AD/AU 全系列）
- [x] バッチ BAT-001〜010（ShedLockで多重起動防止、ADR-0003）
- [x] フロントエンド全19画面（SCR-001〜080、開発用ログインでロール切替）
- [x] CI/CD（GitHub Actions）・IaCコア（Terraform）・メトリクス公開（Micrometer/Prometheus）・E2E（Playwright、工程10の一部）
- [ ] 監視アラート実配線 / IaC残リソース / SESテンプレート登録 / 運用手順書（工程10の残り）

実装計画・残タスクの詳細は `docs/ses_ai_ddd_remaining_tasks.md` / `docs/ses_ai_ddd_implementation_status.md`、
実装順序は詳細設計書 16.2 を参照。
