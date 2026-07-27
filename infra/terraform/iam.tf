data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# タスク実行ロール: イメージpull・ログ出力・Secrets取得（起動時）
resource "aws_iam_role" "ecs_execution" {
  name               = "${local.name_prefix}-ecs-exec-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "ecs_execution_managed" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# 起動時にSecrets Managerの値を注入するための読み取り権限。
# app_login は接続分離の切り替え時に SPRING_DATASOURCE_PASSWORD として注入する。値の投入前でも
# 参照権限だけ先に付けておく（タスク定義から参照し始めた時点で権限不足にならないようにするため）。
data "aws_iam_policy_document" "ecs_execution_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_secretsmanager_secret.payment_webhook_secret.arn,
      aws_secretsmanager_secret.app_login.arn,
      aws_db_instance.main.master_user_secret[0].secret_arn,
    ]
  }
}

resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name   = "${local.name_prefix}-ecs-exec-secrets"
  role   = aws_iam_role.ecs_execution.id
  policy = data.aws_iam_policy_document.ecs_execution_secrets.json
}

# タスクロール: アプリ実行時のAWSアクセス（S3/SES/Secrets）
resource "aws_iam_role" "ecs_task" {
  name               = "${local.name_prefix}-ecs-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

data "aws_iam_policy_document" "ecs_task" {
  statement {
    sid       = "Secrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.payment_webhook_secret.arn]
  }
  statement {
    sid       = "Ses"
    actions   = ["ses:SendEmail", "ses:SendRawEmail"]
    resources = ["*"]
  }
  # ファイルバケットへの presigned URL 発行/読み書き（§10.2）
  statement {
    sid       = "S3Objects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.files.arn}/*"]
  }
  statement {
    sid       = "S3Bucket"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [aws_s3_bucket.files.arn]
  }
  # SQSの権限は持たせない。Outbox配送はアプリ内（ADR-0008）で、アプリはSQSを呼ばない。
  # 将来Workerを別サービスへ切り出す場合は、ADR-0008を差し替えたうえで復活させる。

  # ECS Exec: タスク内のSSMエージェントがチャネルを確立するために必要（リソース指定不可）。
  dynamic "statement" {
    for_each = local.enable_ecs_exec ? [1] : []

    content {
      sid = "EcsExecSsmMessages"
      actions = [
        "ssmmessages:CreateControlChannel",
        "ssmmessages:CreateDataChannel",
        "ssmmessages:OpenControlChannel",
        "ssmmessages:OpenDataChannel",
      ]
      resources = ["*"]
    }
  }

  # ECS Exec: セッションログ出力先の探索（APIがリソース指定に対応しない）。
  dynamic "statement" {
    for_each = local.enable_ecs_exec ? [1] : []

    content {
      sid       = "EcsExecLogGroupDiscovery"
      actions   = ["logs:DescribeLogGroups"]
      resources = ["*"]
    }
  }

  # ECS Exec: セッションログの書き込み（専用ロググループのみ）。
  dynamic "statement" {
    for_each = local.enable_ecs_exec ? [1] : []

    content {
      sid = "EcsExecSessionLogs"
      actions = [
        "logs:CreateLogStream",
        "logs:DescribeLogStreams",
        "logs:PutLogEvents",
      ]
      resources = ["${aws_cloudwatch_log_group.ecs_exec[0].arn}:*"]
    }
  }
}

resource "aws_iam_role_policy" "ecs_task" {
  name   = "${local.name_prefix}-ecs-task-policy"
  role   = aws_iam_role.ecs_task.id
  policy = data.aws_iam_policy_document.ecs_task.json
}
