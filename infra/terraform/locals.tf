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
}
