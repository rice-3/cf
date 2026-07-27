resource "aws_cloudwatch_log_group" "ecs" {
  name              = "/ecs/${local.name_prefix}-backend"
  retention_in_days = 90 # アプリログ90日（基本設計 §7.7）
  tags              = { Name = "${local.name_prefix}-ecs-logs" }
}

# ECS Exec のセッションログ。手動操作の証跡のためアプリログより長く保持する（要件C-17）。
resource "aws_cloudwatch_log_group" "ecs_exec" {
  count             = var.enable_ecs_exec ? 1 : 0
  name              = "/ecs/${local.name_prefix}-exec"
  retention_in_days = 365
  tags              = { Name = "${local.name_prefix}-ecs-exec-logs" }
}
