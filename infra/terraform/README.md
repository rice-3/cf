# CF-Training インフラ（Terraform）

基本設計 §12.3 のAWS構成を Terraform 化したもの（ADR-007）。CD（`.github/workflows/cd.yml`）の
前提となる ECR / ECS / IAM(OIDC) を含む。

## 構成

| ファイル | 内容 |
|---|---|
| `network.tf` | VPC / Public・Private サブネット / IGW / NAT / ルートテーブル |
| `security_groups.tf` | ALB / ECS / RDS / VPCエンドポイント のSG |
| `ecr.tf` | ECRリポジトリ（scan on push、ライフサイクル） |
| `alb.tf` | ALB / ターゲットグループ（/actuator/health）/ リスナー(HTTP + HTTPS) |
| `acm.tf` | ACM証明書（DNS検証、`domain_name` 指定時）/ HTTPS有効化 |
| `ecs.tf` | ECSクラスタ / Fargateタスク定義 / サービス |
| `rds.tf` | PostgreSQL 18（マスターパスワードはSecrets Manager自動管理） |
| `s3.tf` | ファイル用バケット（公開ブロック / バージョニング / SSE / ライフサイクル / CORS） |
| `sqs.tf` | Outbox配送用キュー + DLQ（要判断B、SQS化の受け皿） |
| `ses.tf` | SES送信ドメインID・DKIM（`ses_domain` 指定時）/ 構成セット |
| `cognito.tf` | Cognito User Pool / App Client / ドメイン（認証） |
| `waf.tf` | WAF WebACL（AWSマネージドルール + レート制限）+ ALB関連付け |
| `vpc_endpoints.tf` | S3(Gateway) / ECR・Logs・SecretsManager・SQS・STS(Interface) |
| `monitoring.tf` | CloudWatch アラーム（ALB/ECS/RDS + ビジネス/バッチ）+ ダッシュボード + SNS通知 |
| `iam.tf` | ECSタスク実行ロール / タスクロール（S3/SQS/SES/Secrets） |
| `oidc.tf` | GitHub OIDCプロバイダ + CDデプロイロール |
| `secrets.tf` | Secrets Manager（決済Webhookキー等） |
| `logs.tf` | CloudWatch Logs |
| `outputs.tf` | CDのGitHub Variablesに設定する値 + 追加リソースの参照値 |

### 主な変数（`variables.tf`）

| 変数 | 既定 | 用途 |
|---|---|---|
| `domain_name` | `""` | 設定時にACM証明書 + HTTPS(443) + 80→443リダイレクトを有効化 |
| `route53_zone_id` | `""` | ACMのDNS検証レコードを自動作成（未設定なら `acm_dns_validation_records` を手動登録） |
| `ses_domain` | `""` | SES送信ドメインID・DKIMを作成（`ses_dkim_tokens` をDNSへ登録して検証） |
| `ses_from_address` | `""` | 通知メール送信元（`CF_SES_FROM_ADDRESS`）。空なら `no-reply@<ses_domain>` を導出。両方空は無効ドメインのまま |
| `health_check_grace_period_seconds` | `180` | ECSサービスがALBヘルスチェックを無視する起動猶予（Spring Boot起動+Flyway移行の所要時間） |
| `enable_ecs_exec` | `true` | ECS Exec（SSMセッション/ポートフォワード）。Private配置RDSへの保守経路。productionでは原則 `false` |
| `enable_waf` | `true` | ALBへWAFを関連付け |
| `waf_rate_limit` | `2000` | レートベースルールの1IP/5分上限 |
| `cognito_callback_urls` / `cognito_logout_urls` | localhost | Cognito App Client のOIDC URL |
| `alert_email` | `""` | 設定するとアラームSNSへメール購読を作成 |
| `metrics_namespace` | `CF/Training` | ビジネス/バッチ指標のCloudWatch名前空間（Prometheus→CloudWatch発行先） |
| `api_p95_latency_threshold_seconds` | `1` | APIレイテンシ p95 アラーム閾値（秒） |

## 使い方

```bash
cd infra/terraform

# state用S3/DynamoDBは事前に用意し、partial backendで指定する（backend.tf参照）
terraform init \
  -backend-config="bucket=<tfstate-bucket>" \
  -backend-config="key=dev/terraform.tfstate" \
  -backend-config="region=ap-northeast-1" \
  -backend-config="dynamodb_table=<tflock-table>" \
  -backend-config="encrypt=true"

cp terraform.tfvars.example terraform.tfvars   # 値を編集
terraform plan
terraform apply
```

### アプリへ渡す環境変数（`ecs.tf`）

DataSource は **Spring Boot の Relaxed Binding に従う名前**で注入する。`DB_URL` のような独自名は
`application-{profile}.yml` で `${DB_URL}` と明示しない限り束縛されず、起動時に DataSource 解決が失敗する。

| 環境変数 | 供給元 |
|---|---|
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` | Terraform（RDSエンドポイント / `db_username`） |
| `SPRING_DATASOURCE_PASSWORD` | Secrets Manager（RDS管理シークレットの `password` キー） |
| `SPRING_FLYWAY_USER` | Terraform（`db_username` = オーナー。移行は常にオーナーが実行する） |
| `SPRING_FLYWAY_PASSWORD` | Secrets Manager（RDS管理シークレットの `password` キー） |
| `COGNITO_ISSUER` / `CF_FILE_BUCKET` / `CF_FILE_KEY_PREFIX` / `CF_SES_CONFIGURATION_SET` / `CF_SES_FROM_ADDRESS` / `AWS_REGION` | Terraform |
| `CF_OUTBOX_SQS_QUEUE_URL` | Terraform（**現状アプリ側に読む実装が無い**。Outbox は `InProcessOutboxDispatcher`。要判断B） |
| `CF_PAYMENT_WEBHOOK_SECRET` | Secrets Manager（値は apply 後に手動投入） |

`CF_` 系の短い名前は Relaxed Binding では束縛されない。`application.yml` に
`${CF_FILE_BUCKET:...}` のようなプレースホルダが**書かれているものだけ**が効く。
`ecs.tf` に変数を足すときは、必ず `application.yml` 側の受け口も併せて用意すること
（束縛されない変数は起動を止めず、既定値のまま無言で動く）。

`environment` が `dev` 以外のときは、上記に加えて
`SPRINGDOC_API_DOCS_ENABLED=false` / `SPRINGDOC_SWAGGER_UI_ENABLED=false` を注入する（要判断H）。

### 内部向けパスの外部遮断（要判断H）

`alb.tf` のリスナールールで、インターネットからの到達を塞ぐ。

| ルール | 対象 | 適用範囲 |
|---|---|---|
| `block_actuator`（優先度100） | `/actuator`・`/actuator/*` | 全環境 |
| `block_api_docs`（優先度110） | `/swagger-ui/*`・`/swagger-ui.html`・`/v3/api-docs*` | `environment != "dev"` |

ルールは**実際に転送を行うリスナー**（HTTPS有効時は443、無効時は80）に付く。

- **ヘルスチェックは影響を受けない**。ターゲットグループのヘルスチェックはロードバランサー
  ノードからターゲットへ直接送られ、リスナールールを経由しない。
- **メトリクス収集も影響を受けない**。Collectorサイドカーは同一タスク内の `localhost` から
  `/actuator/prometheus` を取得する。

> Flyway 用の接続は配線済み。現時点では `SPRING_DATASOURCE_*` もオーナーを指しているため実質同一接続であり、
> 挙動は変わらない。実行時接続を `cf_app_login` へ切り替える手順は後述（DBロール作成が先）。

## CDとの連携

`terraform apply` 後、`terraform output` の値をGitHubリポジトリの **Variables** に設定すると
`cd.yml` が動作する。

| output | GitHub Variable |
|---|---|
| `aws_region` | `AWS_REGION` |
| `deploy_role_arn` | `AWS_ROLE_ARN` |
| `ecr_repository` | `ECR_REPOSITORY` |
| `ecs_cluster` | `ECS_CLUSTER` |
| `ecs_service` | `ECS_SERVICE` |
| `ecs_task_family` | `ECS_TASK_FAMILY` |
| `container_name` | `CONTAINER_NAME` |

### デプロイロールの信頼条件（要判断G）

`oidc.tf` の `sub` 条件は `repo:<owner>/<repo>:environment:{dev,staging}` に限定してある。

`cd.yml` の deploy job は `environment:` を指定しており、job が Environment を参照すると
GitHub の `sub` クレームは `repo:<owner>/<repo>:environment:<名前>` になる。
**`ref:refs/heads/main` の形にすると CD が必ず AssumeRole に失敗する。**

この条件はブランチを縛らないので、**GitHub 側で Environment の保護ルールを必ず設定する**
（`Settings > Environments` → Deployment branches を `main` に限定、Required reviewers を有効化）。
手順は `docs/ops/aws-contract-build-runbook.md` §20.4.1。

`workflow_dispatch` の Environment 選択肢を増やすときは、`cd.yml` と `oidc.tf` の両方に
同じ名前を追加すること。

## 検証

CI（`.github/workflows/terraform.yml`）で `fmt -check` / `init -backend=false` / `validate` を実行。
`apply` はAWS認証情報が必要なため手動運用（本リポジトリからの自動applyは行わない）。

## アプリDBユーザーのプロビジョニング（最小権限）

RDSのマスターユーザー（`db_username`、既定 `cf_app`）はSecrets Managerで自動管理され、**DDL/移行の実行者**
（オーナー）となる。アプリの**実行時接続**は、DDL権限を持たない最小権限ユーザーに分離する（基本設計 §11.4）。
Terraformの `postgresql` provider はDB到達性が必要で `validate`/CIで扱えないため、DB内の作業は以下で行う:

1. **グループロール + 権限**（Flyway移行、versioned・自動適用・冪等）:
   `backend/src/main/resources/db/migration/V202607230001__create_app_runtime_role.sql`
   — `cf_app_rw`（NOLOGIN）を作成し、`public` スキーマの既存/将来テーブルへ **DMLのみ**付与
   （`ALTER DEFAULT PRIVILEGES` で将来の移行にも追従）。DDLは付与しない。
2. **ログインユーザー**（ブートストラップSQL、資格情報はGit管理外）:
   `infra/db/create-app-user.sql`
   ```bash
   PGPASSWORD=<owner_pw> psql -h <host> -U cf_app -d <db> \
     -v app_user=cf_app_login -v app_password="$(openssl rand -base64 24)" \
     -f infra/db/create-app-user.sql
   ```
   生成パスワードは Secrets Manager の `<prefix>/app-login-password`
   （`aws_secretsmanager_secret.app_login`、apply で作成済み・値は空）へ投入する:

   ```bash
   aws secretsmanager put-secret-value \
     --secret-id "$(terraform output -raw app_login_secret_id)" \
     --secret-string '<生成パスワード>'
   ```

   RDSは Private サブネット・`publicly_accessible=false` のため、ローカルから直接は接続できない。
   `enable_ecs_exec = true`（既定）なら **SSMポートフォワード**で経路を作る（実行中のECSタスクを踏み台にする。
   コンテナイメージに psql は含まれないため、psql はローカルで動かす）:

   ```bash
   TASK=$(aws ecs list-tasks --cluster <cluster> --service-name <service> --query "taskArns[0]" --output text)
   RUNTIME=$(aws ecs describe-tasks --cluster <cluster> --tasks "$TASK" \
     --query "tasks[0].containers[0].runtimeId" --output text)

   aws ssm start-session \
     --target "ecs:<cluster>_$(basename "$TASK")_${RUNTIME}" \
     --document-name AWS-StartPortForwardingSessionToRemoteHost \
     --parameters '{"host":["<rds-endpoint>"],"portNumber":["5432"],"localPortNumber":["15432"]}'
   # 別ターミナルで psql -h localhost -p 15432 -U cf_app -d cf
   ```

   ローカルに **Session Manager plugin** が必要。セッション内容は `/ecs/<prefix>-exec` ロググループへ
   記録される（手動操作の証跡、要件C-17）。

### アプリ側の接続分離（本番）

実行時接続を最小権限ユーザーに、移行はオーナーで実行するよう分離する:

- `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` = `cf_app_login`（最小権限、実行時）
- `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` = `cf_app`（オーナー、移行時）

Flyway 側（`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`）と Secret の器
（`aws_secretsmanager_secret.app_login`）・IAM 参照権限は **配線済み**。現状は `SPRING_DATASOURCE_*` も
オーナーを指すため実質単一接続で、挙動は分離前と同じ。

**残りは切り替えのみ**。DBロールが実在しない状態で先に切り替えるとアプリが起動できないため、順序を守る:

1. Flyway 移行 `V202607230001`（`cf_app_rw` 作成）が適用済みであること
2. 上記手順で `cf_app_login` を作成し、パスワードを `app_login_secret_id` の Secret へ投入
3. `ecs.tf` を 2 行だけ変更して apply
   - `SPRING_DATASOURCE_USERNAME` の `value` を `cf_app_login` へ
   - `SPRING_DATASOURCE_PASSWORD` の `valueFrom` を `aws_secretsmanager_secret.app_login.arn` へ
4. タスクが起動し `/actuator/health` が UP になることを確認（失敗時は 3 を戻して即 apply）

> local/test プロファイルは簡便のため単一ユーザー（`cf_app`）のままとし、分離は dev 以上で適用する。
> 上記モデルはローカルDB（docker compose）で検証済み: `cf_app_login` は SELECT/INSERT/UPDATE/DELETE 可、
> `CREATE TABLE` 等のDDLは `permission denied`、オーナー作成の新テーブルも自動でSELECT可。

## 監視・アラート（`monitoring.tf`）

- インフラアラーム（ALB 5xx・p95 / ECS CPU・メモリ / RDS CPU・空き容量・接続数）は apply 後すぐ有効。
- ビジネス/バッチアラーム（Outbox/通知/返金/バッチ最終成功経過）は `var.metrics_namespace` のカスタム指標に対して定義。
  これらは `/actuator/prometheus` を **CloudWatch Agent(Prometheus) / ADOT Collector** で収集し当該名前空間へ
  発行するパイプライン（ECSサイドカー等）を apply 時に構成した後に有効化される。
- 通知先は SNSトピック（`alert_email` でメール購読）。閾値は `docs/ops/monitoring.md` を正とする。

## 未対応（今後）

- ACMのDNS検証を伴う `apply`（`domain_name` / `route53_zone_id` 設定後）とHTTPS疎通確認
- SESドメイン検証の完了（DKIM CNAME登録）とサンドボックス解除
- SESメールテンプレートの実登録（`NotificationTemplateCatalog` を正、残 §3.1）
- メトリクスパイプライン（CloudWatch Agent(Prometheus)/ADOT）でビジネス/バッチ指標を発行する構成
- 実AWSでの `apply` 検証（現状は `fmt`/`validate` のみ）
