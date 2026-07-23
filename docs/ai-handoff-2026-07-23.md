# AI作業ハンドオフ（2026-07-23 セッション）

次回AIが本リポジトリで作業を再開するための引き継ぎ文書。**最新の残タスクは
`docs/ses_ai_ddd_remaining_tasks.md` を正**とし、本書はこのセッションの成果と作業の勘所をまとめる。

---

## 1. このセッションで完了したこと（新しい順・コミット付き）

| コミット | 内容 |
|---|---|
| `9225621` | fix: `next` を 16.2.11 へ更新（新規HIGH脆弱性 CVE-2026-64641/64642/64645、Trivyが検出） |
| `337c813` | 監視アラート/ダッシュボードを Terraform 化（`monitoring.tf`、§2.1） |
| `7169dfe` | 最小権限アプリDBユーザー（Flyway `V202607230001` + `infra/db/create-app-user.sql`） |
| `aa28a65` | 未カバーAWSリソースを Terraform 化（ACM/S3/SQS/SES/Cognito/WAF/VPCe、§2.1） |
| `4b6e3d8` | ADR-0004/0005/0006 起票（BFF配置 / 決済非同期UI / 本文プレーンテキスト、判断D） |
| `8a928a5` | Swagger UI + contract-first型生成(openapi-typescript) + Java整形(Eclipse JDT)、§4.1 |
| `d6d8ede` | 運用手順書 `docs/ops/runbook.md`（§3.2 完了） |
| `364523d` | Playwright E2E（`frontend/e2e/`）+ `e2e.yml`（§3.1 完了） |
| `a55d1df` | fix: spotless折返し整合 + `sharp` HIGH脆弱性(0.35へ) |
| `4ac460f` | 監視メトリクス公開(Micrometer/Prometheus) + アラート閾値 `docs/ops/monitoring.md`（§2.1） |
| `de6ff38` | 運用者向け 支援/返金検索API + SCR-060/061 一覧・検索UI化（§2.2 完了） |

設計書 `.docx`（`G:\マイドライブ\CF\`）も v1.x の md から pandoc で再出力済み（リポジトリ外）。

## 2. 現在の状態

- **バックエンド全機能 + フロント全19画面 + CI/CD/スキャン/IaC(コード)/メトリクス/E2E/運用手順** が完了。
- **CIワークフロー(全7種)は緑を維持**: CI / CodeQL(JS-TS) / Semgrep(JVM SAST) / Security scan(Trivy) /
  OpenAPI compatibility / E2E / Terraform。
- **残タスクはほぼ「実AWS環境が必要」なものだけ**（詳細は残タスク文書 §2.1/§3.1/§6）:
  - 実 `terraform apply` と疎通確認、メトリクスパイプライン(CW Agent/ADOT)構成、SESテンプレ登録、
    本番の接続分離(最小権限DBユーザー)適用、Cognito実結合(判断E)、監査アーカイブS3(判断A)、Outbox→SQS(判断B) 等。
  - 非AWSで残るのは **CodeQL の Kotlin 2.4 対応（上流=GitHub 待ち）** のみ。現状 Semgrep で代替済み。
    → 「上流待ち」= GitHub製 CodeQL の Kotlin エクストラクタが 2.4 未対応。バージョンは下げない方針（Semgrep で穴は塞がっている）。

## 3. 作業のやり方（このプロジェクトの確立ワークフロー）

各タスクは必ず次を回す:

1. **実装** → 2. **ローカル検証** → 3. **コミット** → 4. **push** → 5. **CI全workflow緑を確認** → 6. **正直に報告**（検証できない項目は明記）。

- push 先: `https://github.com/rice-3/cf.git` の `main`。
- **CI確認は `gh` CLI 無しで REST API**: トークンは `git credential fill` から取得し、
  `GET /repos/rice-3/cf/actions/runs?head_sha=<SHA>` を conclusion が出揃うまでポーリング（バックグラウンド実行が有効）。
- コミット前に必ず **secret スキャン**（`git diff` を password/secret/key で grep）。
- ステージは **`':!*.lnk'` を必ず除外**（リポジトリ直下に無関係な `.lnk` がある）。`.env.local`/`.terraform/`/tfstate はgitignore。
- コミットメッセージ末尾に `Co-Authored-By: Claude ...`。

## 4. 環境・落とし穴（重要）

- **OS/シェル**: Windows。PowerShell と Bash(Git Bash) 併用。作業ディレクトリは `F:\11\CF`。
- **ビルドは JDK 25 で**: `spotlessCheck` は JVM依存の整形差が出るため、必ず
  `-Dorg.gradle.java.home=C:\Users\ardou\.gradle\jdks\amazon_com_inc_-25-amd64-windows.2` を付けて
  `spotlessApply`/`build` する（CIは JDK25）。過去に JDK差で spotless が CIのみ落ちた。
- **Java整形**: google/palantir-java-format は JDK25 の javac 内部API変更で動かない。
  **Eclipse JDT フォーマッタ**を採用（`backend/spotless-java.properties`、コメントは保全しコードのみ整形）。
- **Kotlin ktlint**: `max-line-length`/`binary-expression-wrapping` は無効化済（`.editorconfig` + build.gradle）。
- **OpenAPI 鮮度ゲート**: API変更時は spec が変わる。`OpenApiSpecIntegrationTest` が失敗したら
  `backend/build/openapi-actual.yaml` を `docs/api/openapi.yaml` へコピーしてコミット。
- **contract-first 型**: spec変更後は `cd frontend && npm run gen:api-types`（`src/lib/generated/api.ts`）。
  CIに再生成差分ゲートあり（`ci.yml`）。
- **Trivy fs ゲート**は修正可能な HIGH/CRITICAL で失敗する。**新規CVE公開で突然赤くなる**ことがある
  （このセッションで sharp・next を実際に更新した）。npm の推移的依存は `overrides` で引き上げる。
- **Terraform 検証はローカルに terraform 不要**。docker で:
  `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/wd" -w /wd hashicorp/terraform:latest {fmt|validate}`
  （`init -backend=false` 済みならvalidate可）。apply はAWS認証が要るので不可。
- **テストのプロファイル**: `test` はスケジューリング/Outbox/バッチ無効（`application-test.yml`）。
  バッチ検証はUseCase/バッチメソッドを直接呼ぶ。結合テストは Testcontainers(PostgreSQL 18, Docker必須)。
- **ローカル起動**: `docker compose -f infra/docker-compose.yml up -d postgres`（5433）→ backend `bootRun --args=--spring.profiles.active=local` → frontend `npm run build && npm run test:e2e`。
  local は `.env.local`(gitignore) が `DEV_USER_ID` を入れており既定ログイン状態になる点に注意。

## 5. 主要な場所

- 残タスク/状況: `docs/ses_ai_ddd_remaining_tasks.md`（**まずこれ**）、`docs/ses_ai_ddd_implementation_status.md`
- 規約: `AGENTS.md`（AIコーディング規約）、ADR: `docs/adr/ADR-0001〜0006`
- 運用: `docs/ops/monitoring.md`（メトリクス/閾値）、`docs/ops/runbook.md`（運用手順）
- IaC: `infra/terraform/`（`README.md` に一覧・変数・DBユーザー方針）、DBブートストラップ: `infra/db/create-app-user.sql`
- 上位設計: `G:\マイドライブ\CF\`（基本設計/詳細設計の md・docx）

## 6. 次にやるなら

- **非AWSで先行できるコード作業はほぼ完了**。新規要望が来たらまず残タスク文書 §2.1/§3.1/§6 を確認。
- AWSが用意できたら: `terraform apply` → outputs を GitHub Variables へ → `cd.yml` 有効化 →
  メトリクスパイプライン(CW Agent/ADOT サイドカー) → SESテンプレ登録 → 本番の接続分離適用。
- CodeQL Kotlin は上流対応後に `codeql.yml` へ `java-kotlin` 追加を判断。
