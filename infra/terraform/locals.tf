locals {
  # 命名規約 {system}-{environment}-{component}-{resource}（基本設計 §12.4）
  name_prefix = "${var.project}-${var.environment}"

  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  # /16 を /20 のサブネットへ分割（public: index, private: index + az_count）
  public_subnet_cidrs  = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 4, i)]
  private_subnet_cidrs = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 4, i + var.az_count)]

  # ドメイン指定時のみHTTPS(ACM)を有効化する
  enable_https = var.domain_name != ""

  # COGNITO_ISSUER: 明示指定があればそれを、無ければ作成するUser Poolから導出する
  cognito_issuer = var.cognito_issuer != "" ? var.cognito_issuer : "https://cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.main.id}"

  s3_file_bucket = "${local.name_prefix}-files"

  # 監査アーカイブ（BAT-009）。ファイル用バケットとは分離する（ADR-0009）。
  s3_audit_archive_bucket = "${local.name_prefix}-audit-archive"

  # 通知メールの送信元（CF_SES_FROM_ADDRESS）。明示指定 > ses_domain から導出 > アプリ既定と同じ無効ドメイン。
  ses_from_address = var.ses_from_address != "" ? var.ses_from_address : (var.ses_domain != "" ? "no-reply@${var.ses_domain}" : "no-reply@example.invalid")

  # ECS Exec は「本番では常時開けない」を既定にする（手順書 §14.5）。
  # 明示指定があればそれに従い、無指定なら production だけ false になる。
  # 有効時はタスクへ対話シェルで入れるため、経路の存在自体を平時は残さない。
  enable_ecs_exec = var.enable_ecs_exec != null ? var.enable_ecs_exec : var.environment != "production"
}
