resource "aws_cloudwatch_log_group" "ecs" {
  name              = "/ecs/${local.name_prefix}-backend"
  retention_in_days = 90 # アプリログ90日（基本設計 §7.7）
  tags              = { Name = "${local.name_prefix}-ecs-logs" }
}
