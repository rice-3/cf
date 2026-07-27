resource "aws_ecs_cluster" "main" {
  name = "${local.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled" # メトリクス（基本設計 §11.4）
  }

  # ECS Exec のセッション内容をCloudWatchへ記録する（手動操作は監査対象、要件C-17）。
  dynamic "configuration" {
    for_each = var.enable_ecs_exec ? [1] : []

    content {
      execute_command_configuration {
        logging = "OVERRIDE"

        log_configuration {
          cloud_watch_log_group_name = aws_cloudwatch_log_group.ecs_exec[0].name
        }
      }
    }
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
      # 変数名は Spring Boot の Relaxed Binding に合わせる（SPRING_DATASOURCE_URL → spring.datasource.url）。
      # `DB_URL` のような独自名は application-{profile}.yml で明示しない限り束縛されず、起動に失敗する。
      environment = concat([
        { name = "SPRING_PROFILES_ACTIVE", value = var.environment },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/${var.db_name}" },
        { name = "SPRING_DATASOURCE_USERNAME", value = var.db_username },
        # 移行(Flyway)はオーナーで実行する。実行時接続を cf_app_login へ分離した後も
        # DDLはオーナーが持つため、ここは常に db_username（= RDSマスター）を指す。
        { name = "SPRING_FLYWAY_USER", value = var.db_username },
        { name = "COGNITO_ISSUER", value = local.cognito_issuer },
        # 受理するアプリクライアント（ADR-0007）。同一User Poolに別クライアントを足しても、
        # ここに無いクライアントが発行したトークンは 401 になる。
        { name = "CF_IDENTITY_COGNITO_ALLOWED_CLIENT_IDS", value = aws_cognito_user_pool_client.web.id },
        { name = "CF_FILE_BUCKET", value = aws_s3_bucket.files.bucket },
        # S3キーの先頭に付く環境識別子（詳細設計 §10.2: env/userId/fileId/...）。
        # 未注入だとアプリ既定の "local" のままになり、環境間でキー空間が分離されない。
        { name = "CF_FILE_KEY_PREFIX", value = var.environment },
        # TODO(question): Outbox配送は現状 InProcessOutboxDispatcher（アプリ内）で、この変数を読む実装が無い。
        # SQSへ切り替えるか否かは要判断B。キューと task role の権限だけ先に存在している状態。
        { name = "CF_OUTBOX_SQS_QUEUE_URL", value = aws_sqs_queue.outbox.url },
        { name = "CF_SES_CONFIGURATION_SET", value = aws_sesv2_configuration_set.main.configuration_set_name },
        # 送信元。ses_domain / ses_from_address 未設定時はアプリ既定と同じ無効ドメインが入る（送信は失敗する）
        { name = "CF_SES_FROM_ADDRESS", value = local.ses_from_address },
        { name = "AWS_REGION", value = var.aws_region },
        ],
        # Swagger UI / OpenAPI spec は dev 以外では配信自体を止める（要判断H・多層防御の内側）。
        # ALB のリスナールール（alb.tf）が外側で塞ぐが、VPC内から直接タスクへ到達する経路も
        # あるため、アプリ側でも無効化する。ローカル実測で束縛を確認済み（2026-07-27）。
        var.environment == "dev" ? [] : [
          { name = "SPRINGDOC_API_DOCS_ENABLED", value = "false" },
          { name = "SPRINGDOC_SWAGGER_UI_ENABLED", value = "false" },
      ])
      secrets = [
        # RDS管理のマスターシークレット(JSON)の password キーを注入
        # 接続分離の適用時は、この行だけ aws_secretsmanager_secret.app_login.arn へ差し替える（README参照）。
        { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" },
        { name = "SPRING_FLYWAY_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" },
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

  # 起動〜Flyway移行の間にALBのヘルスチェック（30s × unhealthy 3回 ≒ 90秒）で
  # 落とされないための猶予。既定0のままだと起動途中のタスクが置き換えループに入る。
  health_check_grace_period_seconds = var.health_check_grace_period_seconds

  # Private配置のRDSへの保守経路（SSMポートフォワード）。実行にはIAM側の ecs:ExecuteCommand も必要。
  enable_execute_command = var.enable_ecs_exec

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
