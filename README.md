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

## 実装状況

第1段階（工程1〜4）と第2段階の工程5〜7が完了。

- [x] Shared Kernel（ID / Money / Clock / 例外階層 / 共通型）
- [x] Project 集約（作成・更新・審査申請・取消・承認・公開・終了判定）
- [x] ReviewRequest 集約（開始・承認・差戻し・却下）
- [x] FileObject 集約 + S3 Adapter（API-FL-001/002。local/testはStub、dev以上はAWS SDK）
- [x] Support 集約 + Idempotency-Key 処理・リターン数量予約（API-FD-001〜004）
- [x] Payment 集約 + 決済Sandbox・Webhook受信（API-PY-001）
- [x] Outbox配送Worker（BAT-006。`FOR UPDATE SKIP LOCKED` + 指数Backoff）
- [x] Flyway DDL（identity / project / review / outbox / audit / file / funding / payment）
- [x] Transactional Outbox / 監査ログ記録
- [x] Refund / Notification 集約とバッチ8本（BAT-001〜005, 007〜009）
- [x] 運用操作API（API-RF-001/002 返金要求・再実行、API-PY-002 決済照合）
- [ ] Identity / Admin / Audit API（工程9）
- [ ] 監視・CI/CD・E2E・運用手順（工程10）

実装計画・残タスクの詳細は `docs/ses_ai_ddd_implementation_status.md`、
実装順序は詳細設計書 16.2 を参照。
