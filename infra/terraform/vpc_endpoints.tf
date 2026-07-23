# VPCエンドポイント（基本設計 §12.3）。privateサブネットからAWSサービスへNAT経由せず接続し、
# コストとセキュリティ（トラフィックをVPC内に閉じる）を改善する。

# S3: ゲートウェイ型（ルートテーブルに関連付け）。ECRのレイヤ取得やファイルI/Oに有効。
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]

  tags = { Name = "${local.name_prefix}-vpce-s3" }
}

# インターフェース型エンドポイント（ENIをprivateサブネットに配置）。
locals {
  interface_endpoints = [
    "ecr.api",
    "ecr.dkr",
    "logs",
    "secretsmanager",
    "sqs",
    "sts",
  ]
}

resource "aws_vpc_endpoint" "interface" {
  for_each = toset(local.interface_endpoints)

  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.vpce.id]
  private_dns_enabled = true

  tags = { Name = "${local.name_prefix}-vpce-${each.value}" }
}
