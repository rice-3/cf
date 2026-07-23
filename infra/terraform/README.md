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
| `enable_waf` | `true` | ALBへWAFを関連付け |
| `waf_rate_limit` | `2000` | レートベースルールの1IP/5分上限 |
| `cognito_callback_urls` / `cognito_logout_urls` | localhost | Cognito App Client のOIDC URL |

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
   生成パスワードは Secrets Manager に保存する。

### アプリ側の接続分離（本番）

実行時接続を最小権限ユーザーに、移行はオーナーで実行するよう分離する:

- `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` = `cf_app_login`（最小権限、実行時）
- `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` = `cf_app`（オーナー、移行時）

> local/test プロファイルは簡便のため単一ユーザー（`cf_app`）のままとし、分離は dev 以上で適用する。
> 上記モデルはローカルDB（docker compose）で検証済み: `cf_app_login` は SELECT/INSERT/UPDATE/DELETE 可、
> `CREATE TABLE` 等のDDLは `permission denied`、オーナー作成の新テーブルも自動でSELECT可。

## 未対応（今後）

- ACMのDNS検証を伴う `apply`（`domain_name` / `route53_zone_id` 設定後）とHTTPS疎通確認
- SESドメイン検証の完了（DKIM CNAME登録）とサンドボックス解除
- SESメールテンプレートの実登録（`NotificationTemplateCatalog` を正、残 §3.1）
- 監視アラート（CloudWatch Alarm / ダッシュボード）のコード化（残 §2.1）
- 実AWSでの `apply` 検証（現状は `fmt`/`validate` のみ）
