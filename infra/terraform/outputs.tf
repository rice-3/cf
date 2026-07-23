# cd.yml のリポジトリVariablesへ設定する値（terraform apply後に確認）。
output "aws_region" {
  value = var.aws_region
}

output "deploy_role_arn" {
  description = "GitHub Variables: AWS_ROLE_ARN"
  value       = aws_iam_role.github_deploy.arn
}

output "ecr_repository" {
  description = "GitHub Variables: ECR_REPOSITORY"
  value       = aws_ecr_repository.backend.name
}

output "ecr_repository_url" {
  value = aws_ecr_repository.backend.repository_url
}

output "ecs_cluster" {
  description = "GitHub Variables: ECS_CLUSTER"
  value       = aws_ecs_cluster.main.name
}

output "ecs_service" {
  description = "GitHub Variables: ECS_SERVICE"
  value       = aws_ecs_service.backend.name
}

output "ecs_task_family" {
  description = "GitHub Variables: ECS_TASK_FAMILY"
  value       = aws_ecs_task_definition.backend.family
}

output "container_name" {
  description = "GitHub Variables: CONTAINER_NAME"
  value       = local.container_name
}

output "alb_dns_name" {
  description = "アプリのエンドポイント（ALB）"
  value       = aws_lb.main.dns_name
}

# ---- 追加リソース（§2.1） ----------------------------------------------------

output "s3_file_bucket" {
  description = "ファイルバケット名（アプリ CF_FILE_BUCKET）"
  value       = aws_s3_bucket.files.bucket
}

output "outbox_queue_url" {
  description = "Outbox SQS キューURL（CF_OUTBOX_SQS_QUEUE_URL）"
  value       = aws_sqs_queue.outbox.url
}

output "cognito_user_pool_id" {
  value = aws_cognito_user_pool.main.id
}

output "cognito_web_client_id" {
  value = aws_cognito_user_pool_client.web.id
}

output "cognito_issuer" {
  description = "Resource Server の COGNITO_ISSUER"
  value       = local.cognito_issuer
}

output "cognito_domain" {
  description = "Cognito Hosted UI ドメイン"
  value       = "${aws_cognito_user_pool_domain.main.domain}.auth.${var.aws_region}.amazoncognito.com"
}

output "waf_web_acl_arn" {
  description = "WAF WebACL ARN（enable_waf時）"
  value       = var.enable_waf ? aws_wafv2_web_acl.main[0].arn : null
}

output "acm_certificate_arn" {
  description = "ACM証明書ARN（domain_name指定時）"
  value       = local.enable_https ? aws_acm_certificate.main[0].arn : null
}

output "acm_dns_validation_records" {
  description = "ACM DNS検証レコード（route53_zone_id未指定時に手動登録する）"
  value       = local.enable_https ? aws_acm_certificate.main[0].domain_validation_options : []
}

output "ses_dkim_tokens" {
  description = "SES DKIM トークン（DNSへCNAME登録）。ses_domain指定時のみ。"
  value       = var.ses_domain != "" ? aws_sesv2_email_identity.domain[0].dkim_signing_attributes[0].tokens : []
}

output "alerts_sns_topic_arn" {
  description = "アラーム通知先SNSトピックARN"
  value       = aws_sns_topic.alerts.arn
}

output "dashboard_name" {
  description = "CloudWatchダッシュボード名"
  value       = aws_cloudwatch_dashboard.main.dashboard_name
}
