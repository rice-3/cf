# アプリが参照する秘密情報（Secrets Manager、基本設計 §10.3/§11.4）。
# 値はTerraformでは管理せず（Gitに残さない）、作成後にコンソール/CLIで設定する。
resource "aws_secretsmanager_secret" "payment_webhook_secret" {
  name        = "${local.name_prefix}/payment-webhook-secret"
  description = "決済Sandbox Webhook署名検証キー（CF_PAYMENT_WEBHOOK_SECRET）"
}

# アプリ実行時DBユーザー（cf_app_login）のパスワード。最小権限での実行時接続に使う（§11.4）。
# ロール実体はDB内にしか作れないため、apply後に infra/db/create-app-user.sql で作成し、
# 生成したパスワードを本シークレットへ手動投入する（値はTerraform管理外＝stateに残さない）。
# 投入前に SPRING_DATASOURCE_USERNAME を切り替えると起動できないため、切り替えは最後に行う（README参照）。
resource "aws_secretsmanager_secret" "app_login" {
  name        = "${local.name_prefix}/app-login-password"
  description = "アプリ実行時DBユーザー cf_app_login のパスワード（SPRING_DATASOURCE_PASSWORD）"
}

# DBのマスター認証情報は RDS の manage_master_user_password が Secrets Manager に自動生成する
# （rds.tf 参照）。アプリはそのシークレットARNを参照する。オーナー = Flyway 実行者。
