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

## アプリDBユーザーのプロビジョニング

RDSのマスターユーザー（`db_username`、既定 `cf_app`）はSecrets Managerで自動管理される。
最小権限の**アプリ専用DBユーザー**をマスターとは別に用意する場合、Terraformの `postgresql` provider は
DBへのネットワーク到達性が必要で `validate`/CIでは扱えないため、**Flyway移行またはブートストラップSQL**で
作成する運用とする（例: 初回のみ `CREATE ROLE cf_app_rw LOGIN ...` + スキーマ権限付与、資格情報は
Secrets Managerへ）。本リポジトリのIaCはネットワーク非依存な範囲に限定する。

## 未対応（今後）

- ACMのDNS検証を伴う `apply`（`domain_name` / `route53_zone_id` 設定後）とHTTPS疎通確認
- SESドメイン検証の完了（DKIM CNAME登録）とサンドボックス解除
- SESメールテンプレートの実登録（`NotificationTemplateCatalog` を正、残 §3.1）
- 監視アラート（CloudWatch Alarm / ダッシュボード）のコード化（残 §2.1）
- 実AWSでの `apply` 検証（現状は `fmt`/`validate` のみ）
