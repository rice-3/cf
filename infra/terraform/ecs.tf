resource "aws_ecs_cluster" "main" {
  name = "${local.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled" # メトリクス（基本設計 §11.4）
  }
}

locals {
  container_name = "${local.name_prefix}-backend"
  image_uri      = "${aws_ecr_repository.backend.repository_url}:${var.image_tag}"
}

# 初期タスク定義。以後のイメージ更新はCD（cd.yml）が新リビジョンを登録する。
resource "aws_ecs_task_definition" "backend" {
  family                   = "${local.name_prefix}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name      = local.container_name
      image     = local.image_uri
      essential = true
      portMappings = [
        { containerPort = var.container_port, protocol = "tcp" }
      ]
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = var.environment },
        { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/${var.db_name}" },
        { name = "DB_USERNAME", value = var.db_username },
        { name = "COGNITO_ISSUER", value = var.cognito_issuer },
        { name = "CF_FILE_BUCKET", value = "${local.name_prefix}-files" },
      ]
      secrets = [
        # RDS管理のマスターシークレット(JSON)の password キーを注入
        { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" },
        { name = "CF_PAYMENT_WEBHOOK_SECRET", valueFrom = aws_secretsmanager_secret.payment_webhook_secret.arn },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "backend"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "backend" {
  name            = "${local.name_prefix}-backend"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.ecs.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = local.container_name
    container_port   = var.container_port
  }

  # CDがタスク定義（イメージ）を更新するため、Terraformでは差分を無視する
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_lb_listener.http]
}
