# アプリが参照する秘密情報（Secrets Manager、基本設計 §10.3/§11.4）。
# 値はTerraformでは管理せず（Gitに残さない）、作成後にコンソール/CLIで設定する。
resource "aws_secretsmanager_secret" "payment_webhook_secret" {
  name        = "${local.name_prefix}/payment-webhook-secret"
  description = "決済Sandbox Webhook署名検証キー（CF_PAYMENT_WEBHOOK_SECRET）"
}

# DBのマスター認証情報は RDS の manage_master_user_password が Secrets Manager に自動生成する
# （rds.tf 参照）。アプリはそのシークレットARNを参照する。
