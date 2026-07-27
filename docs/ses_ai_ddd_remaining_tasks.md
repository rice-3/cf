# CF-Training 残タスク一覧

- 対象リポジトリ: `F:\11\CF`（GitHub: `https://github.com/rice-3/cf.git`）
- 上位文書: 基本設計 BD-CF-001 v1.2 / 詳細設計 DD-CF-001 v1.2（`G:\マイドライブ\CF\`）
- 更新日: 2026-07-27（§2.3 突き合わせ、要判断 H/G/C/B/A（ADR-0007〜0009）、直PUT、enable_ecs_exec、GitHub Environment を決定・実装。**AWS不要の残タスクは完了**）
- 実装済み範囲の詳細は `ses_ai_ddd_implementation_status.md` を参照。
- 本書は**残タスク**を主役とする。完了済みは §5 に要約のみ記載。

---

## 1. サマリ

| # | 工程（詳細設計 §16.2） | 状態 |
|---|---|---|
| 1〜9 | Shared Kernel 〜 Identity/Admin/Audit（バックエンド全機能） | ✅ 完了 |
| — | フロントエンド 全19画面（基本設計 §5.2） | ✅ 完了 |
| 10 | CI/CD・スキャン・IaC（コア）・メトリクス公開・E2E・運用手順書・AWS構築手順書 | ✅ 完了 |
| 10 | 監視アラート実配線・IaC実apply・SES登録 | ⬜ **残タスクの中心（AWS依存）** |

- バックエンドの業務API（API-PJ/RV/FL/FD/PY/RF/US/AD/AU）は全系列実装済み。
- 業務フローは「起案 → 審査 → 公開 → 支援 → 決済 → 募集終了 → 返金 → 通知」まで一気通貫で動作。
- CI/CD 一式（ビルド/テスト/SAST/依存・コンテナscan/OpenAPI互換/Terraform検証/CD雛形）は構築・ゲート化済み。
- 監視メトリクス（Micrometer → `/actuator/prometheus`、ビジネス滞留/バッチ稼働/APIレイテンシ）と
  アラート閾値定義（`docs/ops/monitoring.md`）は完了。残るは監視基盤への実配線（§2.1）。
- E2E（Playwright）で「起案→審査承認」ジャーニー・ロール別アクセス制御・運用コンソールを検証（`e2e.yml`）。
- 運用手順書（`docs/ops/runbook.md`）、AWS契約・構築手順書（`docs/ops/aws-contract-build-runbook.md`）を整備。
- AWS契約〜CD稼働までの手順を文書化した際のコード読解で、apply してもECSタスクが起動しない設定不整合を3件検出し、
  **修正済み（§2.2、ローカルで fmt/validate 通過を確認）**。
- **残るのはほぼAWS依存の運用基盤（監視アラートの実配線・実apply・SES登録）** と、少数の要判断事項・軽微なフォローアップ。
- §2.3 のタスク定義⇄アプリ設定の突き合わせを実施し、**さらに2件の無言の不整合を検出・修正**
  （SES設定セットが束縛されない / S3キー接頭辞が全環境 `local`）。
- そこで見つかった「production でも Swagger UI・actuator が無認証公開される」問題は
  **要判断H として決定・実装済み**（ALB＋アプリの多層防御、Swagger UI は dev のみ。§2.4）。
- **要判断G（OIDC信頼条件）も決定・実装済み**（`sub` を `environment:{dev,staging}` へ限定。§2.5）。
  ブランチ限定は GitHub の Environment 保護ルールで行うため、**apply 後に GitHub 側の設定が必須**。
- **要判断C（Cognito JIT自動登録）も決定・実装済み**（許容。ADR-0007。§2.6）。あわせて
  **IDトークンがBearerとして通っていた問題**を `token_use` 検証で塞ぎ、`client_id` 検証と
  JIT登録の監査記録を追加した。
- **要判断B（Outbox配送）**はアプリ内配送で確定し未使用のSQS資産を削除（ADR-0008。§2.7）。
  **要判断A（監査アーカイブ）**は専用バケット / GLACIER_IR / 保持1年で確定（ADR-0009。§2.10）。
  あわせて**S3へ出さずにDB行を削除していた状態**を解消した。
- **AWS不要の残タスクは完了。** 残るのは実apply・実疎通確認（AWS必須）、予算判断（要判断F）、
  CodeQL の Kotlin 対応（上流待ち）のみ。
- **進め方**: AWS契約・apply はローカルテスト完了後。

### 残タスク早見表

AWS要否で3分割する（A → B の順に進める）。

#### A. AWS不要 — ローカルで着手できる（当面の作業対象）

| 優先 | 区分 | タスク | 節 |
|---|---|---|---|
| — | IaC/検証 | ~~タスク定義 ⇄ アプリ設定の突き合わせ~~ → **実施済み**（2件を修正、2件を要判断へ繰り上げ） | 2.3 |
| — | セキュリティ | ~~要判断H: Swagger UI / OpenAPI spec / actuator の外部公開~~ → **決定・実装済み**（ALB＋アプリの多層防御、Swagger UI は dev のみ） | 2.4 |
| — | セキュリティ | ~~要判断G: GitHub OIDC の信頼条件~~ → **決定・実装済み**（`sub` を `environment:{dev,staging}` へ限定。**GitHub側のEnvironment作成が apply 後に必須**） | 2.5 |
| — | 認証 | ~~要判断C: 未登録Cognito SubjectのJIT自動登録の可否~~ → **決定・実装済み**（許容。`token_use` / `client_id` 検証とJIT監査記録を追加。ADR-0007） | 2.6 |
| — | 設計/実装 | ~~要判断B: Outbox配送のSQS切替~~ → **決定・実装済み**（アプリ内配送で確定。未使用のSQS資産を削除。ADR-0008） | 2.7 |
| — | 実装 | ~~メイン画像のブラウザ直PUT~~ → **実装済み**（`uploadRequired` で分岐。§2.8） | 2.8 |
| — | 運用判断 | ~~production の `enable_ecs_exec` 既定値~~ → **決定・実装済み**（`environment` から導出。production は `false`） | 2.9 |

> **表 A（AWS不要）は全件完了。** 表 B のうち GitHub Environment（AWS不要だった）と
> 要判断A（決定・実装はローカルで完結）も済んだため、**残るのは実AWSでの操作のみ**である。

#### B. AWS必須 — 認証情報・実アカウントが要る（ローカルテスト完了後）

| 優先 | 区分 | タスク | 節 |
|---|---|---|---|
| 高 | IaC | 実AWSでの `apply`・疎通確認（state置き場の手動作成、ECR先行applyを含む） | 2.1 |
| 高 | 監視 | メトリクスパイプライン構成（ADOT/CW Agent サイドカー）＋実apply | 2.1 |
| 高 | DB | 接続分離の**切り替え**: `cf_app_login` 作成 → Secret値投入 → `ecs.tf` 2行変更 → apply | 2.1 |
| — | CD | ~~GitHub Environment（`dev` / `staging`）の作成と保護ルール設定~~ → **設定済み 2026-07-27**（AWS不要だった。§2.5） | 2.5 |
| 中 | 通知 | SESテンプレート実登録・サンドボックス解除申請 | 3.1 |
| 中 | 判断 | ~~要判断A: 監査アーカイブの実出力先~~ → **決定・実装済み**（ADR-0009。§2.10）。**残るのは実S3への出力確認のみ** | 2.10 |
| 中 | 判断 | 要判断E: Cognito実User Poolでの結合確認 | 6-E |

#### C. 外部待ち — こちらから動かせない

| 区分 | 内容 | 節 |
|---|---|---|
| 予算 | 要判断F: dev環境の稼働モードとコスト構成（予算責任者の決定） | 6-F |
| 上流 | CodeQL の Kotlin 2.4 対応（現状 Semgrep で代替中） | 4.1 |

> 完了済みで早見表から落としたもの: apply前の必須修正3件（§2.2）、Flyway接続分離のTerraform配線（§2.1）、
> ECS Exec による保守経路（§2.1）、contract-first型生成/swagger-ui・Java整形・設計書docx再出力（§4.1、いずれも §5.3）。
> 要判断D は ADR-0004/0005/0006 として起票済み。

---

## 2. 残タスク（優先度: 高）

### 2.1 IaC — 未カバーのAWSリソースと監視の実配線（Terraform、ADR-007）

- [x] **未カバーのAWSリソースの Terraform コード化** — ACM+HTTPS(`acm.tf`/`alb.tf`) / S3ファイルバケット(`s3.tf`) /
      ~~SQS+DLQ(`sqs.tf`)~~（ADR-0008 で削除。§2.7） / SES ドメイン検証・DKIM(`ses.tf`) / Cognito User Pool・Client(`cognito.tf`) /
      WAF(`waf.tf`) / VPCエンドポイント(`vpc_endpoints.tf`) を追加し、IAM/ECS環境変数へ配線。
      `terraform fmt -check` / `init` / `validate` 済み（provider aws v5.100）。ドメイン/SESはvarでゲート。
- [ ] **実AWSでの apply と疎通確認** — AWS認証情報が必要なため未実施。**手順は
      `docs/ops/aws-contract-build-runbook.md` に確定済み**（契約 → Budgets → Identity Center →
      state用S3/DynamoDB作成 → tfvars → apply → GitHub Variables → CD → 疎通確認 → destroy）。
      前提として §2.2 の必須修正3件を先に適用すること。
  - [ ] state置き場（S3 `cftraining-tfstate-<AccountID>` + DynamoDB `cftraining-tflock`）はTerraform管理外のため
        手動作成（手順書 §10）。
  - [ ] 初回は `-target=aws_ecr_repository.backend` で先にECRを作り `:bootstrap` タグを push してから全体applyする
        （初期タスク定義が存在しないイメージを参照するため。手順書 §13.2）。
  - [ ] `apply` 後の手作業: Secrets Manager の決済Webhookキー値投入 / Cognito App Client Secret のフロント設定 /
        GitHub Variables 7件 / ACM・SES の DNS レコード / SNS購読承認（手順書 §14）。
- [x] **Private配置RDSへの保守経路（ECS Exec）** — 2026-07-26 対応。`enable_ecs_exec` で
      `aws_ecs_service.enable_execute_command` + タスクロールへ `ssmmessages` 4アクション、
      セッションログ用の権限を付与。**セッション内容は `/ecs/<prefix>-exec`（保持365日）へ記録**する
      （クラスタの `execute_command_configuration` を `OVERRIDE`。手動操作の証跡、要件C-17）。
      実行イメージ（`amazoncorretto:25`）に psql は含まれないため、**SSMポートフォワードでローカルの psql を
      RDSへ繋ぐ**方式とした（手順書 §14.5 に手順。Session Manager plugin が必要）。
  - [x] production で `enable_ecs_exec = false` とするか → **決定・実装済み 2026-07-27**（§2.9）。
        既定値を `environment` から導出し、production のみ `false`。明示指定で一時有効化できる。
- [x] **アプリDBユーザーのプロビジョニング（最小権限）** — Flyway移行
      `V202607230001__create_app_runtime_role.sql`（`cf_app_rw`: DMLのみ + 将来テーブル自動付与、冪等）と
      ブートストラップSQL `infra/db/create-app-user.sql`（ログインユーザー、資格情報はGit外）を追加。
      ローカルDBで検証済み（SELECT/DML可・DDL拒否・将来テーブル自動SELECT可）、Testcontainersビルドも通過。
  - [x] 接続分離の**Terraform側配線**（2026-07-27）— Secretの器 `aws_secretsmanager_secret.app_login`
        （`<prefix>/app-login-password`、値はTerraform管理外）、実行ロールへの参照権限、
        `SPRING_FLYWAY_USER`（=`db_username`）/ `SPRING_FLYWAY_PASSWORD`（RDSマスターの`password`）注入、
        output `app_login_secret_id` を追加。`fmt -check` / `validate` 通過。
        現状は `SPRING_DATASOURCE_*` もオーナーを指すため挙動は分離前と同じ（無害）。
  - [ ] **残（apply後・AWS必須）**: ①`cf_app_login` ロール作成（`infra/db/create-app-user.sql` を
        SSMポートフォワード経由で実行）②生成パスワードを `app_login_secret_id` の Secret へ投入
        ③`ecs.tf` の `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` を2行切り替えて apply。
        **順序厳守**（ロール不在で切り替えると起動不能）。手順は `infra/terraform/README.md`。
- [x] **CloudWatch Alarm / ダッシュボードの Terraform 化** — `monitoring.tf` に SNSトピック（`alert_email` 購読）、
      インフラアラーム（ALB 5xx・p95レイテンシ / ECS CPU・メモリ / RDS CPU・空き容量・接続数）、
      ビジネス/バッチアラーム（Outbox滞留・通知失敗・返金失敗/再試行待ち・バッチ最終成功経過、
      閾値は `docs/ops/monitoring.md` 準拠）、CloudWatchダッシュボードを定義。`validate` 済み。
  - [ ] **メトリクスパイプラインの構成（apply時）** — ビジネス/バッチメトリクス（`var.metrics_namespace`）は
        `/actuator/prometheus` を CloudWatch Agent(Prometheus) / ADOT Collector で収集し CloudWatch へ発行する
        構成が前提（ECSサイドカー等）。インフラアラームは apply 後すぐ有効。実 apply とメール購読確認は AWS 必須。
        推奨構成は ADOT Collector のサイドカー（Prometheus receiver → `awsemf` exporter、タスクロールへ
        `cloudwatch:PutMetricData` 追加）。詳細は手順書 §17。

### 2.2 apply前の設定不整合（3件）— **対応済み 2026-07-26**

`docs/ops/aws-contract-build-runbook.md` 作成時のコード読解で検出。未修正のまま apply すると
タスク起動失敗のループで NAT / Fargate / ALB の課金だけが発生する状態だった。3件とも修正済み。

- [x] **(1) DataSource の環境変数名が Spring と不一致（起動不能）** — `ecs.tf` の
      `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` を Relaxed Binding に従う
      `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`（secrets）へ改名。
      `application-dev.yml` は追加せず Terraform 側だけで閉じた。
  - [x] 接続分離用の `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` 注入（2026-07-27、§2.1 参照）。
        実行時側の `cf_app_login` への切り替えは apply 後の作業として §2.1 に残す。
- [x] **(2) SES送信元アドレスが未注入** — `local.ses_from_address` を `CF_SES_FROM_ADDRESS` として注入。
      優先順位は `var.ses_from_address` → `no-reply@${var.ses_domain}` → `no-reply@example.invalid`(アプリ既定)。
      変数 `ses_from_address` を追加（`variables.tf` / `terraform.tfvars.example` / README 更新済み）。
      **`ses_domain` / `ses_from_address` のいずれも空だと従来どおり送信失敗する**点は仕様として明記。
- [x] **(3) ヘルスチェック猶予が未設定** — `aws_ecs_service.backend` に
      `health_check_grace_period_seconds = var.health_check_grace_period_seconds`（既定 `180`）を追加。

> 3件とも `terraform fmt` / `validate` は通るため CI では検出できなかった（**実行時の不整合**）。
> ローカル検証は 2026-07-27 に実施済み（Terraform 1.15.8 導入後、`fmt -check -recursive` / `init -backend=false` /
> `validate` すべて通過）。ただしこれは構文・型が正しいことしか示さない。同種の不整合は §2.3 で洗い出す。

### 2.3 タスク定義 ⇄ アプリ設定の突き合わせ（AWS不要・**実施済み 2026-07-27**）

§2.2 の3件は「`validate` は通るが apply するとタスクが起動しない」類で、CIでは原理的に検出できない。
同じ穴が他に残っていないかを、**AWS に触れずコード読解で**洗い出した。

- [x] `ecs.tf` の `environment` / `secrets` と、アプリが実際に読む設定キーの全件突き合わせ
      → **2件の不整合を検出・修正**（下表 (1)(2)）。1件を要判断へ繰り上げ（(3)）。
- [x] `dev` プロファイルで**未定義のまま参照している設定**がないか
      → `application-dev.yml` は存在せず `application.yml` のみで起動する。local 固有の定義
      （`spring.datasource.*` / `cf.seed.dev-users`）への依存は無い。**問題なし**。
      ただしプロファイル別ファイルが無いこと自体が (3) の原因になっている。
- [x] Flyway 実行順とアプリ起動の関係
      → `V202607230001` は `cf_app_rw`（NOLOGIN のグループロール）の作成と GRANT のみで、
      ログインユーザー `cf_app_login` の作成は `infra/db/create-app-user.sql` による手動作業。
      実行時接続の切り替えは Secret 値投入後の最後の手順なので、**順序の矛盾は無い**。
      `alter default privileges` により後続 Migration が作るテーブルにも権限が追従する。
- [x] バッチ／ShedLock／Outbox がマルチインスタンス（`desired_count` > 1）で破綻しないか
      → ADR-0003 の適用範囲表どおり BAT-001/002/004/005/007/008 に加え BAT-010 も
      `@SchedulerLock` 付与済み。`OutboxWorker` のみ意図的に除外（`FOR UPDATE SKIP LOCKED`
      の競合コンシューマ設計）。**破綻しない**。なお BAT-009 監査アーカイブは未実装
      （要判断A 待ち。`cf.batch.audit-archive-cron` の設定値だけが先に存在する）。
- [x] ヘルスチェック経路と `/actuator/health` の露出設定の整合
      → ターゲットグループの `path = /actuator/health`・`port = var.container_port(8080)`、
      アプリ側は `server.port: 8080`、`SecurityConfig` で `/actuator/health/**` を `permitAll`。
      exposure にも `health` を含む。**整合している**。

#### 検出した不整合

| # | 内容 | 対応 |
|---|---|---|
| (1) | `CF_SES_CONFIGURATION_SET` が**どこにも束縛されない**。`SesProperties.configurationSetName` の束縛元は `cf.notification.ses.configuration-set-name`（環境変数なら `CF_NOTIFICATION_SES_CONFIGURATION_SET_NAME`）で、`application.yml` にプレースホルダも無かった。SES送信は成功するが設定セットが付かず、**バウンス・苦情のイベント追跡が無言で無効**になる。§2.2 と完全に同型 | **修正済み**: `application.yml` に `configuration-set-name: ${CF_SES_CONFIGURATION_SET:}` を追加。未設定時は空文字が入るため `SesNotificationSender` 側で空白を除外 |
| (2) | `CF_FILE_KEY_PREFIX` を `ecs.tf` が注入していない。アプリ既定 `local` に落ちるため、**dev / staging / production すべてが同じキー空間**を使う（詳細設計 §10.2 は `env/userId/fileId/...`） | **修正済み**: `ecs.tf` で `var.environment` を注入 |
| (3) | `springdoc`（`/swagger-ui.html`・`/v3/api-docs`）と `/actuator/prometheus`・`/actuator/info` が `SecurityConfig` で `permitAll`、ALB は全パスをターゲットグループへ転送。プロファイル別ファイルが無いため**production でも無認証で公開される**。`ecs.tf` にも無効化する環境変数は無い | **要判断H として起票**（§6-H） |
| (4) | `CF_OUTBOX_SQS_QUEUE_URL` を読む実装が無い（Outbox は `InProcessOutboxDispatcher` のまま）。キューと task role の SQS 権限だけが存在し、「SQS 経由で配送されているつもり」になりうる | 要判断B の未決に起因。`ecs.tf` に `TODO(question)` を明記して可視化 |

> 上記のうち再発防止として残す観点は §7 へ追記済み。

---

### 2.4 内部向けパスの外部遮断（要判断H・**実装済み 2026-07-27**）

§2.3-(3) で検出した「production でも Swagger UI と actuator が無認証公開される」問題への対応。
**ALB（ネットワーク層）とアプリ（設定層）の多層防御**とし、Swagger UI は **dev のみ残す**。

| 層 | 対象 | 実装 |
|---|---|---|
| ALB | `/actuator`・`/actuator/*` | `aws_lb_listener_rule.block_actuator`（全環境・優先度100）で 404 固定応答 |
| ALB | `/swagger-ui/*`・`/swagger-ui.html`・`/v3/api-docs*` | `aws_lb_listener_rule.block_api_docs`（`environment != "dev"` のみ・優先度110）で 404 固定応答 |
| アプリ | springdoc | `ecs.tf` で `environment != "dev"` のとき `SPRINGDOC_API_DOCS_ENABLED=false` / `SPRINGDOC_SWAGGER_UI_ENABLED=false` |

ルールは**実際に転送を行うリスナー**（HTTPS有効時は443、無効時は80）へ付ける。
HTTPS有効時の80番は443へリダイレクトするだけなのでルールは不要。

#### この設計が壊さないことの根拠（いずれも確認済み）

| 懸念 | 結論 |
|---|---|
| ALBヘルスチェックが落ちないか | **落ちない**。ターゲットグループのヘルスチェックはロードバランサーノードからターゲットへ直接送られ、**リスナールールを経由しない** |
| メトリクス収集が止まらないか | **止まらない**。ADOT/CW Agent は同一タスク内の `localhost` から `/actuator/prometheus` を取得する（§2.1）。`exposure.include` から `prometheus` を外す案は、サイドカーからも取得できなくなるため採らなかった |
| フロントの型生成が壊れないか | **壊れない**。`frontend` はコミット済みの `docs/api/openapi.yaml` から生成する（`npm run gen:api-types`）。live の `/v3/api-docs` に依存しない |
| CIのspec一致検証が壊れないか | **壊れない**。`OpenApiSpecIntegrationTest` は test プロファイルで動き、そこでは springdoc を無効化しない |

#### 実装中に判明した副作用と、その修正

springdoc を無効化すると `/v3/api-docs` は `SecurityConfig` の `permitAll` を通過した先で
ハンドラが無く `NoResourceFoundException` になる。これが `GlobalExceptionHandler` の汎用
ハンドラへ落ち、**404 ではなく 500 を返していた**（ローカル実測で確認）。

外部スキャナが `/v3/api-docs` を叩くだけで 5xx アラート（`docs/ops/monitoring.md`）を
誘発できてしまうため、`NoResourceFoundException` を 404 として扱うハンドラを追加した。

#### 環境変数名の実測（推測で決めない）

`springdoc.api-docs.enabled` の環境変数形は、ハイフンの扱いで
`SPRINGDOC_APIDOCS_ENABLED` と `SPRINGDOC_API_DOCS_ENABLED` のどちらになるか紛らわしい。
§2.3 がまさにこの種の取り違えを潰すタスクなので、**ローカルで実際に起動して確認した**。

| 条件 | `/v3/api-docs` | `/swagger-ui.html` | `/actuator/prometheus` |
|---|---|---|---|
| `SPRINGDOC_API_DOCS_ENABLED=false` / `SPRINGDOC_SWAGGER_UI_ENABLED=false` | 404 | 404 | 200 |
| 対照（環境変数なし） | 200 | 302（`/swagger-ui/index.html`へ） | 200 |

`SPRINGDOC_API_DOCS_ENABLED` 形で束縛される（`@ConditionalOnProperty` 経由のため
ハイフンをアンダースコアにした形も解決される）。prometheus はどちらでも 200 のままで、
サイドカー収集に影響しないことも併せて確認した。

---

### 2.5 GitHub OIDC の信頼条件の限定（要判断G・**実装済み 2026-07-27**）

`oidc.tf` の `sub` 条件が `repo:<owner>/<repo>:*` で、リポジトリ内の任意の ref から
デプロイロールを assume できる状態だった。

#### 素直な「`ref:refs/heads/main` へ限定」は誤り

`cd.yml` の deploy job は `environment: ${{ inputs.environment }}` を指定している。
**job が Environment を参照すると、GitHubが発行する `sub` クレームは
`repo:<owner>/<repo>:environment:<名前>` になり、`ref:refs/heads/main` の形にはならない。**
ref 形へ変更すると CD は必ず AssumeRole に失敗する。

runbook §20.4 は変更前まさに `ref:refs/heads/main` を推奨していたため、あわせて訂正した。

#### 採用した構成（IAMとGitHubで役割分担）

| 層 | 固定するもの | 実装 |
|---|---|---|
| IAM（`oidc.tf`） | **どの Environment 経由か** | `sub` を `repo:<owner>/<repo>:environment:dev` と `:environment:staging` の2つに `StringEquals` |
| GitHub（Environment保護） | **どのブランチから dispatch できるか / 承認者** | `Settings > Environments` で Deployment branches を `main` に限定、Required reviewers を有効化 |

**IAM 側の条件はブランチを縛らない。** `sub` に ref が含まれないため、GitHub 側の保護ルールを
設定しない限り任意ブランチの `cd.yml` から dev/staging へデプロイできる。
手順は runbook §20.4.1 に記載。

#### GitHub 側の設定（**完了 2026-07-27**）

当初「apply後の作業」として表Bに置いていたが、**AWSは不要**だったため先に設定した。
`gh` CLI で実施し、API で設定内容を検証済み。

| Environment | Deployment branches | Required reviewers |
|---|---|---|
| `dev` | `main` のみ | なし |
| `staging` | `main` のみ | `rice-3`（要件C-17「AI単独の本番反映禁止」） |

- `deployment_branch_policy` は `custom_branch_policies: true` とし、`main` を明示登録した
  （`protected_branches` 方式にすると、ブランチ保護ルールの有無に挙動が依存するため）
- `prevent_self_review` は `false`。承認者が1人しかいないため、有効にすると自分が起票した
  デプロイを誰も承認できなくなる。**承認者を増やしたら `true` に切り替えること**
- 保護ルールは public リポジトリでは無料で使える。**private 化する場合は
  GitHub Pro / Team 以上が必要**で、プラン次第でこの保護が外れる点に注意

#### 運用上の注意

`workflow_dispatch` の Environment 選択肢を増やすときは、**`cd.yml` と `oidc.tf` の両方**へ
同じ名前を追加する。片方だけだと AssumeRole が
`Not authorized to perform sts:AssumeRoleWithWebIdentity` で失敗する。

#### 検討したが採らなかった案

`job_workflow_ref`（`<repo>/.github/workflows/cd.yml@refs/heads/main`）を IAM 条件に追加すると、
GitHub 側の設定漏れがあってもブランチを固定できる。ただし**この条件キーが実際に効くかを
AWS 認証情報が無い現状では検証できず**、効かなければ初回 CD が AssumeRole に失敗する。
未検証の条件を apply 前に入れない方針とし、見送った（apply 後に実機で確認してから追加する）。

---

### 2.6 Cognito JIT自動登録の承認とトークン受入条件（要判断C・**実装済み 2026-07-27**）

`CognitoJwtAuthenticationConverter` が未登録Cognito Subjectを初回アクセス時に自動登録
（JIT provisioning）していた件。**許容する**と決定し、**ADR-0007** として起票した。
コード内の `TODO(question)` は削除済み。

#### 判断の根拠になった事実

設計書には **`app_user` 行の生成手段の規定が無い**。

| 観点 | 設計書 |
|---|---|
| 画面 | `SCR-001 ログイン` のみ。**新規会員登録画面が無い** |
| API | `API-US-001/002`・`API-AD-001/002/003` のみ。**ユーザー作成APIも招待APIも無い** |
| §9.1 | 「Cognito Subjectと内部UserIdを分離」「ロールはDBを正とする」のみ |

JITを否定すると、設計書に無いユーザー作成API・招待UI・Cognito AdminCreateUser連携
（task roleへ `cognito-idp` 権限追加）を新規に作ることになる。支援者が自分で参加できることは
クラウドファンディングの前提でもあるため、JITを承認した。

#### 追加したガード

JITは「トークンさえ通れば利用者が増える」経路なので、トークンの受入条件を狭めた。

| # | ガード | 理由 |
|---|---|---|
| 1 | `token_use == "access"` を検証 | **CognitoはIDトークンとアクセストークンを同じissuer・同じJWKSで発行する**ため、署名とissuerの検証だけでは区別できず、**IDトークンをBearerに載せても通っていた** |
| 2 | `client_id` を許可リストで検証 | 同一User Poolに別クライアントを足しても、`ecs.tf` が注入した Webクライアント のトークンだけを受理する。未設定（local/test）では検証しない |
| 3 | JIT付与ロールは SUPPORTER 固定 | 昇格は API-AD-002（ADMIN専用・監査対象）を必ず通す |
| 4 | JIT登録を監査ログへ記録（`USER_JIT_PROVISION`） | 誰がいつ自動登録されたかを追える |

#### プロフィールはプレースホルダになる

**Cognitoのアクセストークンには `email` / `name` クレームが無い**（IDトークン専用）。
ガード1でアクセストークンのみ受理するため、JIT時に氏名・メールは取得できない。

変更前の実装はトークンから読もうとして取れなければ暗黙にフォールバックしていたが、
**アクセストークンでは必ずフォールバックする**ため、プレースホルダであることを明示する形に
変えた（`<sub>@cognito.invalid` / `(未設定)`）。本人が API-US-002 で更新する前提。

> 支援フローが実名・連絡先を要求する場合、プロフィール未設定を検出して入力を促す
> 画面側の制御が別途必要になる。ADR-0007 に影響として記載した（本対応の範囲外）。

#### 構造の整理

登録処理を `JitProvisioningService`（application層）へ移し、変換器はトークン検証と変換に
徹する形にした。監査記録も application 層で行う（`AdminUserService` / `ProfileService` と同じ）。
同一Subjectの同時初回アクセスは `uq_app_user_cognito_subject` で片方が失敗するため、
`DuplicateKeyException` を捕捉して既存行を読み直す。

単体テスト `CognitoJwtAuthenticationConverterTest`（8ケース）を追加した。

---

### 2.7 Outbox配送はアプリ内で確定（要判断B・**実装済み 2026-07-27**）

**ADR-0008** として起票。設計書（基本設計 §8.1 BAT-006「SQSまたはアプリ内」、
詳細設計 §9.1 BAT-003「SQS/内部Handler」）は**どちらも許容**しており、
これは仕様違反の是正ではなく2案のどちらを正式構成とするかの決定だった。

#### アプリ内配送を選んだ理由

購読側は同一JVM内の `@EventListener` が3つだけ（`NotificationEventHandler` /
`PaymentRequestedHandler` / `ProjectFailedHandler`）。詳細設計 §3 のモジュール構成にあった
`app-worker`（Batch / SQS Worker）は **ADR-0001 で単一backendプロジェクトへ統合済み**なので、
SQSを挟んでも「Worker → SQS → 同一アプリのポーラー → 同じ3ハンドラ」となり、
プロセス分離という本来の利得が出ない。一方コストは、SDK依存・ポーラー実装・
at-least-onceの重複対処・DLQ運用・ローカル用LocalStackが実在する。

マルチインスタンスの懸念は §2.3 で解消済み（`FOR UPDATE SKIP LOCKED` の競合コンシューマ設計で、
ADR-0003 により意図的に ShedLock 対象外）。

#### 削除したもの

| 対象 | 内容 |
|---|---|
| `infra/terraform/sqs.tf` | ファイルごと削除（`outbox` / `outbox_dlq`） |
| `infra/terraform/iam.tf` | task role の `Sqs` statement |
| `infra/terraform/ecs.tf` | `CF_OUTBOX_SQS_QUEUE_URL` と `TODO(question)` |
| `infra/terraform/outputs.tf` | `outbox_queue_url` |
| `infra/terraform/vpc_endpoints.tf` | `sqs` インターフェースエンドポイント |

これで §2.3-(4)「渡しているが読まれていない変数」も解消した。
`sqs` エンドポイントの削除は **要判断F（コスト）にも効く**（Interface エンドポイントは
1つあたりAZ数分のENIと時間課金が発生する）。アプリ側のコード変更は無く、コメントのみ更新した。
`OutboxDispatcher` インターフェースは残すので、将来の差し替え口は維持される。

---

### 2.8 メイン画像のブラウザ直PUT（§5.4・**実装済み 2026-07-27**）

`MainImageUploader.tsx` は「発行 → （PUTせず）→ 完了」の順で呼んでおり、
**dev以上の実S3では完了API（API-FL-002）の `headObject` が空振りして必ず失敗する**状態だった。

単純に常時PUTすると、local/test のスタブが返す到達不能URL
（`https://s3.stub.invalid/...`、RFC 6761 の予約TLD）へPUTしてローカルが壊れる。
そこで**発行レスポンスに `uploadRequired` を追加**し、クライアントはこの値で分岐する。

| 実装 | `uploadRequired` |
|---|---|
| `S3FileStorageAdapter`（dev以上） | `true` |
| `StubFileStorageAdapter`（local/test） | `false` |

URLの形（`.invalid` かどうか）で判定させないことを意図している。
PUTの失敗（署名切れ・Content-Type不一致・通信断）は完了APIを呼ばずにエラー表示で止める。

バケットのCORS（`s3.tf` の `aws_s3_bucket_cors_configuration`）は
`PUT/GET/HEAD` 許可・`ETag` 公開で**既に設定済み**だったため追加変更は不要。

契約変更に伴い `docs/api/openapi.yaml` をアプリから再生成し、
`frontend/src/lib/generated/api.ts` を `npm run gen:api-types` で更新した
（CIが生成漏れを検出するため）。レスポンスへの任意プロパティ追加なので
`oasdiff breaking` は通る。

---

### 2.9 production の `enable_ecs_exec` 既定値（§2.1・**決定・実装済み 2026-07-27**）

手順書 §14.5 が既に「production では `enable_ecs_exec = false` を基本とし、必要時だけ
一時的に有効化して作業後に戻す」と定めていたのに、Terraform の既定は `true` のままだった。
**既定値を `environment` から導出する**形にして、記述と実態を一致させた。

```hcl
# locals.tf
enable_ecs_exec = var.enable_ecs_exec != null ? var.enable_ecs_exec : var.environment != "production"
```

- `var.enable_ecs_exec` は `default = null`（nullable）にし、明示指定があればそれを優先する
- 無指定なら **production だけ `false`**、dev / staging は `true`
- production で保守のため一時的に開けるときは明示的に `true` を渡し、作業後に戻す

`false` のときはサービスの `enable_execute_command`、タスクロールの `ssmmessages` 系権限、
Exec用ロググループのすべてが作られない（`ecs.tf` / `iam.tf` / `logs.tf` は
`local.enable_ecs_exec` を参照するよう変更）。**経路の存在自体を平時は残さない。**

---

### 2.10 監査アーカイブの実出力先（要判断A・**決定・実装済み 2026-07-27**）

**ADR-0009** として起票。決定は **専用バケット / GLACIER_IR / 保持1年**。

#### 直していた問題

`LocalAuditArchiveAdapter` は `@Profile` を持たない無条件Beanで、**S3へ何も出さないのに
ハッシュを返していた**。BAT-009 はハッシュが返れば「出力できた」と判断してDB行を削除するため、
dev以上で動かすと**監査ログが出力されずに消える**状態だった。

#### 保持1年の意味（DB保持期間への上乗せ）

BAT-009 がアーカイブするのは**すでにDB保持期限を超えた行**なので、S3側の1年は上乗せ分になる。

| データ | DB | ＋S3 | 実質 | §7.7 要件 |
|---|---|---|---|---|
| 監査ログ | 3年 | 1年 | **4年** | 3年 ✅ |
| AI利用記録 | 1年 | 1年 | **2年** | 1年 ✅ |

#### 決定内容と理由

| 項目 | 決定 | 理由 |
|---|---|---|
| バケット | 専用 `<prefix>-audit-archive` | ファイル用バケットは**ブラウザからの presigned PUT のためCORSを開けており**、キーが presign 経路から到達しうる。`pending/` のライフサイクルも干渉する |
| ストレージクラス | **GLACIER_IR** | 最低保存期間90日で保持1年に収まり、取り出しがミリ秒。DEEP_ARCHIVE は最安だが取り出しに数時間かかり監査調査に向かない |
| 保持 | ライフサイクル 365日 | 上表のとおり要件を満たす |
| アプリ権限 | **`s3:PutObject` のみ** | Get も Delete も与えず、アプリ経由の改ざん・削除を封じる（§7.7「改ざん防止、参照権限限定」） |

**PUT時に直接ストレージクラスを指定する**（ライフサイクルの transition を使わない）。
S3のライフサイクル遷移は既定で128KB未満をGlacier系へ遷移させないため、小さなアーカイブが
STANDARD に残り続ける事故を避ける。

#### 読み直せないので S3 側に検証させる

Get 権限が無いため書き込んだ内容を読み直せない。代わりに PUT へ **SHA-256 チェックサムを添えて
S3側で検証**させる。不一致ならS3が PUT を拒否するので「返ってきたハッシュ = S3が受理した内容の
ハッシュ」が保証される。バケット未設定時は `DependencyException` で落とし、
**ハッシュを返さないことでDB削除を止める**。

#### Object Lock は capability だけ開けて既定の保持ルールは置かない

Object Lock は**バケット作成時にしか有効化できない**ため `object_lock_enabled = true` にしておき、
既定の保持ルールは `audit_archive_lock_days`（既定 `0` = 無効）で制御する。
production で強制する段階になっても**バケットを作り直さずに**変数指定で済む。

既定で有効にしないのは、**COMPLIANCE はルートでも解除できず、指定日数が経つまで
`terraform destroy` が失敗する**ため。要判断F で「都度 apply/destroy」運用が候補に
挙がっている dev では致命的になる。モード既定は `GOVERNANCE`。

#### 残（AWS必須）

**実S3への出力確認のみ。** ローカルでは `LocalAuditArchiveAdapter` が動くため、S3経路そのものは
未検証である。単体テスト（設定の既定値・バケット未設定時の失敗）は追加済み。

---

## 3. 残タスク（優先度: 中）

### 3.1 SESテンプレートのAWS登録

- [ ] 本文は `NotificationTemplateCatalog` を正として定義済み。`aws_sesv2_email_template` またはCLIで
      実登録する（テンプレートIDはカタログのキー）。§2.1 の SES ドメイン検証と併せて実施。
- [ ] **SESサンドボックス解除の申請** — 初期状態は検証済みアドレス宛にしか送信できない。任意宛先へ送るには
      Production access を申請する（承認まで最大24時間）。申請しない運用なら、テスト用受信アドレスを
      個別に検証する（手順書 §14.4）。

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
- **IaC** — `infra/terraform/`。コア（VPC / サブネット / NAT / SG / ECR / ALB / ECS Fargate /
  RDS PostgreSQL 18 / IAM(タスク実行・タスク) / GitHub OIDC + デプロイロール / Secrets Manager /
  CloudWatch Logs）に加え、**未カバーリソースをコード化**: ACM+HTTPS / S3ファイルバケット /
  SQS+DLQ / SESドメイン・DKIM / Cognito User Pool・Client / WAF / VPCエンドポイント。IAM・ECS環境変数へ配線。
  `terraform.yml` で fmt / init / validate（provider aws v5.100）。ドメイン/SESはvarでゲート。
  `apply` はAWS認証が必要なため手動運用（残 §2.1、`infra/terraform/README.md`）。
- **ECS Exec（保守経路）** — `enable_ecs_exec`（無指定なら production 以外で true）でサービスの `enable_execute_command` +
  タスクロールへ `ssmmessages` 4アクション + セッションログ権限。クラスタの `execute_command_configuration` を
  `OVERRIDE` にして `/ecs/<prefix>-exec`（365日）へセッションを記録（要件C-17）。
  Private配置RDSへはSSMポートフォワード経由でローカルの psql を使う（イメージに psql 非同梱のため）。
- **最小権限DBユーザー** — Flyway移行 `V202607230001__create_app_runtime_role.sql`（`cf_app_rw`: DMLのみ・
  将来テーブル自動付与）+ ブートストラップSQL `infra/db/create-app-user.sql`（ログインユーザー）。
  実行時接続を最小権限ユーザー、移行をオーナーに分離する方針（本番はdev以上で適用）。ローカルDBで検証済み。
- **CD** — `cd.yml`（手動 `workflow_dispatch`、環境承認付き）。image build → ECR push → ECS ローリング更新
  （OIDC）。実AWS未提供のため apply/deploy 検証は未実施（残: §2.1 のリソース整備後）。
- **監視メトリクス** — Micrometer + `/actuator/prometheus`（`micrometer-registry-prometheus`）。
  ビジネス滞留（`BusinessMetrics`: outbox/notification/refund の未処理・失敗ゲージ）、
  バッチ稼働（`BatchMetrics`: `cf_batch_last_success_age_seconds` / `cf_batch_runs_total`）、
  通知送信レート（`cf_notification_delivery_total`）、API レイテンシ/5xx（`http.server.requests` ヒストグラム）。
  全メーターに `application` 共通タグ（`ObservabilityConfig`）。アラート閾値は `docs/ops/monitoring.md`。
  結合テスト `MetricsIntegrationTest` で公開を検証。
- **監視アラート/ダッシュボード（IaC）** — `infra/terraform/monitoring.tf`。SNSトピック（メール購読）+
  インフラアラーム（ALB 5xx・p95 / ECS CPU・メモリ / RDS CPU・空き容量・接続数）+ ビジネス/バッチアラーム
  （Outbox/通知/返金/バッチ最終成功経過、閾値は monitoring.md 準拠）+ CloudWatchダッシュボード。`validate` 済。
  ビジネス/バッチ系は Prometheus→CloudWatch パイプライン（`var.metrics_namespace`）の apply 時構成が前提。
- **E2E（Playwright）** — `frontend/e2e/`（`playwright.config.ts`）。ブラウザで「起案→審査承認」ジャーニー、
  ロール別アクセス制御、公開画面、運用コンソール一覧・検索を検証。`e2e.yml` で PostgreSQL(サービス) +
  backend(local, `java -jar`) + frontend(`next start`) を起動して実行。ローカルは
  `docker compose up -d postgres` + `bootRun` + `npm run test:e2e`。公開→支援→決済→返金は
  バッチ・Webhook依存のためバックエンド結合テストで網羅（E2Eは画面到達可能な状態遷移を対象）。
- **運用手順書** — `docs/ops/runbook.md`。バッチ運用（再実行方針）、決済照合、返金の手動対応、
  Outbox滞留・通知失敗、障害切り分け（相関ID/監査ログ/メトリクス）、アラート→一次対応表、
  デプロイ/ロールバックを記載。本番手動変更は監査対象（要件C-17）である旨を明記。
- **AWS契約・構築手順書** — `docs/ops/aws-contract-build-runbook.md`（2026-07-26）。アカウント契約・ルート保護・
  Budgets・IAM Identity Center から、state置き場の手動作成、`terraform.tfvars`、段階apply、apply後の手作業
  （Secrets値/Cognito Client Secret/GitHub Variables/DNS/DBユーザー/SNS購読）、CD実行、疎通確認、破棄までを
  本リポジトリの実定義に沿って記述。あわせて **費用概算（dev常時稼働で月4.3〜5.0万円。支配的なのは
  Interface VPCエンドポイント12 ENIとNAT Gatewayで全体の約6割）** と削減オプション、destroy時の落とし穴
  （S3バージョン残存・ECRイメージ残存・Secrets Managerの30日復旧猶予による名前衝突）を整理。
  本書作成時の読解で §2.2 の不整合3件を検出。

### 5.4 既知の暫定実装（要フォロー）

- **SCR-060/061（OPERATOR）**: 運用者向け検索API（支援検索/返金検索）を追加し、一覧・検索UIへ移行済み。
- ~~**メイン画像アップロード**: dev以上の実S3接続時はブラウザからの直接PUT追加が必要~~
  → **対応済み 2026-07-27**（§2.8）。発行レスポンスの `uploadRequired` で分岐し、
  dev以上は実PUT、local/test のスタブ（到達不能URL）はPUTしない。
  **実S3経路そのものの疎通確認は dev 環境構築後**（表B）。

### 5.5 その他

- [x] git初回コミット・push済み（`main`）。CI 全ワークフロー緑を維持。

---

## 6. 未確定・要判断事項（人間の決定待ち）

| # | 内容 | 影響・対応 |
|---|---|---|
| A | ~~監査アーカイブ（BAT-009）の実出力先~~ → **決定済み（2026-07-27）: 専用バケット / GLACIER_IR / 保持1年** | **ADR-0009** として起票。`TODO(question)` は削除済み。実S3への出力確認のみAWS必須。詳細は §2.10 |
| B | ~~Outbox配送のSQS切替~~ → **決定済み（2026-07-27）: アプリ内配送を正式構成とする。未使用のSQS資産は削除** | **ADR-0008** として起票。詳細は §2.7 |
| C | ~~未登録Cognito Subjectの初回JIT自動登録の可否~~ → **決定済み（2026-07-27）: 許容する。ただしトークン受入条件を狭める** | **ADR-0007** として起票。`TODO(question)` は削除済み。詳細は §2.6 |
| D | ~~ADR-BFF配置 / 決済非同期UI / Rich Text形式 の3件が未起票~~ → **起票済み** | ADR-0004（BFF配置）/ ADR-0005（決済非同期UI）/ ADR-0006（本文プレーンテキスト）。既定動作を追認する形で文書化 |
| E | Cognito実User Poolでの結合確認 | 未実施（テストはlocal/testの開発用ヘッダー認証のみ）。dev環境構築時に実施 |
| F | dev環境の稼働モードとコスト構成 | 常時稼働は月4.0〜4.6万円。Interface VPCエンドポイント（10 ENI、約1.6万円/月）の要否、`desired_count`、Container Insights、WAFの取捨で1.5〜2.0万円まで低減可。「都度 apply/destroy」運用なら数千円。予算責任者の決定が必要（手順書 §3） |
| G | ~~GitHub OIDC の信頼条件~~ → **決定済み（2026-07-27）: `sub` を `environment:{dev,staging}` に限定。ブランチ限定と承認者は GitHub の Environment 保護で行う** | 実装済み。詳細は §2.5 |
| H | ~~Swagger UI / OpenAPI spec / `/actuator/prometheus` の環境別公開可否~~ → **決定済み（2026-07-27）: ALB＋アプリの多層防御。Swagger UI は dev のみ残す** | 実装済み。詳細は §2.4 |

> 解決済みの要判断: 起案者向け通知の宛先解決（ADR-0002）、冪等記録削除バッチ（BAT-010）、
> バッチ多重起動防止（ADR-0003: ShedLock）、BFF配置（ADR-0004）、決済非同期UI（ADR-0005）、
> 本文プレーンテキスト（ADR-0006）。詳細は §5。

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
| ECSの環境変数名 | Spring の Relaxed Binding は `SPRING_DATASOURCE_URL` 形式のみ束縛する。`DB_URL` のような独自名は、`application-{profile}.yml` で `${DB_URL}` と明示しない限り無視される（§2.2-(1) で実際に踏んだ） |
| IaCの検証範囲 | `terraform validate` は構文と型のみ。環境変数名の不一致・ヘルスチェック猶予・未注入の設定値といった**実行時の不整合はCIで検出できない**。apply前にタスク定義とアプリ設定を突き合わせる |
| 環境変数は「起動失敗」より「無言の既定値」が危険 | 束縛されない環境変数は起動を止めない。`@ConfigurationProperties` の既定値やプレースホルダの既定に落ちて**動いているように見える**（§2.3-(1) SES設定セットがnull、(2) S3キー接頭辞が全環境 `local`）。`ecs.tf` に変数を足したら、必ず束縛先のプロパティ名まで辿って確認する |
| プロファイル別ファイルが無い前提 | `application-{dev,staging,production}.yml` は存在せず、dev以上は `application.yml` ＋ ECS環境変数だけで構成される。「本番では無効化する」と `application.yml` のコメントに書いてある設定（springdoc等）は、**環境変数で明示的に上書きしない限り有効のまま**（§2.3-(3)） |
| 渡しているが読まれていない変数 | `CF_OUTBOX_SQS_QUEUE_URL` のように、IaC側だけ先に用意して実装が追随していない変数がある。IaCの存在をもって機能が有効と判断しない（§2.3-(4)） |
| プロファイル指定の無いAdapterは全環境で生きる | `LocalAuditArchiveAdapter` は `@Profile` を持たない無条件Beanで、**S3へ出さないのにハッシュを返し、BAT-009 がDB行を削除していた**。スタブ実装には必ず `@Profile("local","test")` を付け、本番実装と排他にする（§2.10） |
| 書き込み専用の出力先は読み直して検証できない | 監査アーカイブはタスクロールに `s3:PutObject` だけ与えるため GetObject で照合できない。PUT に SHA-256 チェックサムを添えて**S3側に検証させる**（不一致ならPUTが失敗する）。「出力できたか」を自前で確かめられない設計では、サービス側の検証機構を使う（§2.10） |
| S3ライフサイクルは小さいオブジェクトをGlacierへ移さない | 既定で128KB未満は Glacier 系へ遷移しない。小さなアーカイブを確実に安いクラスへ置くには、**PUT時に直接ストレージクラスを指定する**（§2.10） |
| Object Lock は後付けできず destroy を止めうる | バケット作成時にしか有効化できないため capability だけ先に開ける。既定の保持ルールを COMPLIANCE で入れると**ルートでも解除できず期間中 `terraform destroy` が失敗する**（§2.10） |
| `permitAll` のパスで実体が消えると500 | `permitAll` はセキュリティを通過させるだけで、その先にハンドラが無ければ `NoResourceFoundException` になる。汎用ハンドラに落ちると**404ではなく500**になり、外部スキャンだけで5xxアラートを誘発できる。`GlobalExceptionHandler` に404ハンドラを追加済み（§2.4） |
| ALBヘルスチェックとリスナールール | ターゲットグループのヘルスチェックは**リスナールールを経由しない**（LBノードからターゲットへ直接）。`/actuator/*` をリスナールールで404にしても `/actuator/health` の死活監視は生きる（§2.4） |
| ハイフンを含む設定キーの環境変数名 | `springdoc.api-docs.enabled` のようにハイフンを含むキーは、環境変数形が `SPRINGDOC_APIDOCS_ENABLED` か `SPRINGDOC_API_DOCS_ENABLED` か紛らわしい。**推測せず実際に起動して応答で確かめる**（§2.4 に実測表） |
| GitHub OIDC の `sub` は Environment で形が変わる | job が `environment:` を参照すると `sub` は `repo:<owner>/<repo>:environment:<名前>` になり、**`ref:refs/heads/main` の形にならない**。ref 形で絞ると CD が AssumeRole に失敗する。`sub` はブランチを縛らないので、ブランチ限定は GitHub の Environment 保護ルール側で行う（§2.5） |
| CognitoのIDトークンとアクセストークンは見分けがつかない | 両者は**同じissuer・同じJWKS**で発行されるため、署名とissuerの検証だけでは区別できない。Resource Server では `token_use` を必ず検証する。またアクセストークンには `email` / `name` が無い（IDトークン専用）ので、プロフィールをトークンから取る設計にしない（§2.6） |
