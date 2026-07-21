# CF-Training インフラ（Terraform）

基本設計 §12.3 のAWS構成を Terraform 化したもの（ADR-007）。CD（`.github/workflows/cd.yml`）の
前提となる ECR / ECS / IAM(OIDC) を含む。

## 構成

| ファイル | 内容 |
|---|---|
| `network.tf` | VPC / Public・Private サブネット / IGW / NAT / ルートテーブル |
| `security_groups.tf` | ALB / ECS / RDS のSG |
| `ecr.tf` | ECRリポジトリ（scan on push、ライフサイクル） |
| `alb.tf` | ALB / ターゲットグループ（/actuator/health）/ リスナー(HTTP) |
| `ecs.tf` | ECSクラスタ / Fargateタスク定義 / サービス |
| `rds.tf` | PostgreSQL 18（マスターパスワードはSecrets Manager自動管理） |
| `iam.tf` | ECSタスク実行ロール / タスクロール |
| `oidc.tf` | GitHub OIDCプロバイダ + CDデプロイロール |
| `secrets.tf` | Secrets Manager（決済Webhookキー等） |
| `logs.tf` | CloudWatch Logs |
| `outputs.tf` | CDのGitHub Variablesに設定する値 |

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

## 未対応（今後）

- HTTPS(ACM証明書) + 80→443リダイレクト
- S3（ファイル用バケット、§9.2）/ SQS / SES ドメイン検証 / Cognito User Pool のリソース化
- WAF、VPCエンドポイント（§12.3）
- アプリDBユーザーのプロビジョニング（現状はRDSマスター）
