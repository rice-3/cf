# ACM証明書（HTTPS、基本設計 §10.3）。var.domain_name 指定時のみ作成する。
# DNS検証。var.route53_zone_id があれば検証レコードを自動作成、無ければ出力を手動登録する。

resource "aws_acm_certificate" "main" {
  count             = local.enable_https ? 1 : 0
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "${local.name_prefix}-cert" }
}

# Route53ゾーンがある場合のみ、検証用CNAMEを自動作成する。
resource "aws_route53_record" "cert_validation" {
  for_each = local.enable_https && var.route53_zone_id != "" ? {
    for dvo in aws_acm_certificate.main[0].domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      type   = dvo.resource_record_type
      record = dvo.resource_record_value
    }
  } : {}

  zone_id = var.route53_zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 300
}

resource "aws_acm_certificate_validation" "main" {
  count                   = local.enable_https && var.route53_zone_id != "" ? 1 : 0
  certificate_arn         = aws_acm_certificate.main[0].arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]
}
