# 監視・アラート（基本設計 §12.5–12.6 / 詳細設計 §9.3）。閾値は docs/ops/monitoring.md を正とする。
#
# 構成:
# - SNSトピック（アラーム通知先。alert_email 指定時はメール購読）
# - インフラメトリクスのアラーム（ALB / ECS / RDS。CloudWatch標準メトリクスで apply 後すぐ有効）
# - ビジネス/バッチメトリクスのアラーム（var.metrics_namespace。Prometheus→CloudWatch 発行後に有効）
# - CloudWatch ダッシュボード
#
# ビジネス/バッチメトリクスは Micrometer の /actuator/prometheus を CloudWatch Agent(Prometheus) /
# ADOT Collector が収集し var.metrics_namespace へ発行する前提（パイプラインは apply 時に構成）。

resource "aws_sns_topic" "alerts" {
  name = "${local.name_prefix}-alerts"
  tags = { Name = "${local.name_prefix}-alerts" }
}

resource "aws_sns_topic_subscription" "alerts_email" {
  count     = var.alert_email != "" ? 1 : 0
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  alarm_actions = [aws_sns_topic.alerts.arn]

  # バッチ最終成功経過秒の Critical 閾値（周期に応じる、docs/ops/monitoring.md §3）
  batch_age_alarms = {
    "BAT-001-publish"             = 900
    "BAT-002-close-funding"       = 900
    "BAT-004-refund"              = 900
    "BAT-005-notification"        = 900
    "BAT-006-outbox"              = 900
    "BAT-007-reconcile"           = 5400
    "BAT-008-file-cleanup"        = 172800
    "BAT-010-idempotency-cleanup" = 172800
  }
}

# ---- インフラメトリクス（CloudWatch標準、apply後すぐ有効） --------------------

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.name_prefix}-alb-5xx"
  alarm_description   = "ALBが返す5xx（バックエンド不達・エラー）"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  dimensions          = { LoadBalancer = aws_lb.main.arn_suffix }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "target_5xx" {
  alarm_name        = "${local.name_prefix}-target-5xx"
  alarm_description = "アプリ（ターゲット）が返す5xx。5分で5%相当を目安に調整"
  namespace         = "AWS/ApplicationELB"
  metric_name       = "HTTPCode_Target_5XX_Count"
  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.backend.arn_suffix
  }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 25
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "api_latency_p95" {
  alarm_name        = "${local.name_prefix}-api-latency-p95"
  alarm_description = "APIレイテンシ p95 がSLO超過（docs/ops/monitoring.md）"
  namespace         = "AWS/ApplicationELB"
  metric_name       = "TargetResponseTime"
  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.backend.arn_suffix
  }
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 2
  threshold           = var.api_p95_latency_threshold_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "ecs_cpu" {
  alarm_name          = "${local.name_prefix}-ecs-cpu"
  alarm_description   = "ECSサービスCPU使用率が高い"
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  dimensions          = { ClusterName = aws_ecs_cluster.main.name, ServiceName = aws_ecs_service.backend.name }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "ecs_memory" {
  alarm_name          = "${local.name_prefix}-ecs-memory"
  alarm_description   = "ECSサービスメモリ使用率が高い"
  namespace           = "AWS/ECS"
  metric_name         = "MemoryUtilization"
  dimensions          = { ClusterName = aws_ecs_cluster.main.name, ServiceName = aws_ecs_service.backend.name }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${local.name_prefix}-rds-cpu"
  alarm_description   = "RDS CPU使用率が高い"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "${local.name_prefix}-rds-free-storage"
  alarm_description   = "RDS空き容量が少ない（2GiB未満）"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2147483648 # 2 GiB
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_connections" {
  alarm_name          = "${local.name_prefix}-rds-connections"
  alarm_description   = "RDS接続数が多い（コネクションリーク等）"
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# ---- ビジネス/バッチメトリクス（var.metrics_namespace、パイプライン発行後に有効） --

resource "aws_cloudwatch_metric_alarm" "outbox_pending" {
  alarm_name          = "${local.name_prefix}-outbox-pending"
  alarm_description   = "Outbox未配送が滞留（配送停止・連続失敗の疑い）"
  namespace           = var.metrics_namespace
  metric_name         = "cf_outbox_pending_count"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1000
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "outbox_oldest_age" {
  alarm_name          = "${local.name_prefix}-outbox-oldest-age"
  alarm_description   = "Outbox最古未配送の経過が長い（滞留時間SLO超過）"
  namespace           = var.metrics_namespace
  metric_name         = "cf_outbox_oldest_age_seconds"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1800
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "notification_failed" {
  alarm_name          = "${local.name_prefix}-notification-failed"
  alarm_description   = "送信失敗確定の通知が多い（要手動対応）"
  namespace           = var.metrics_namespace
  metric_name         = "cf_notification_failed_count"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 50
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "refund_failed" {
  alarm_name          = "${local.name_prefix}-refund-failed"
  alarm_description   = "FAILEDで滞留する返金（運用者の手動対応が必要）"
  namespace           = var.metrics_namespace
  metric_name         = "cf_refund_failed_count"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "refund_retry_wait" {
  alarm_name          = "${local.name_prefix}-refund-retry-wait"
  alarm_description   = "再試行待ちの返金が多い（決済プロバイダ障害の疑い）"
  namespace           = var.metrics_namespace
  metric_name         = "cf_refund_retry_wait_count"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  threshold           = 100
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# バッチ最終成功経過（batch ディメンション別）。周期に応じ閾値を変える。
resource "aws_cloudwatch_metric_alarm" "batch_stale" {
  for_each = local.batch_age_alarms

  alarm_name          = "${local.name_prefix}-batch-stale-${each.key}"
  alarm_description   = "${each.key} が閾値時間内に成功していない（停止・滞留の疑い）"
  namespace           = var.metrics_namespace
  metric_name         = "cf_batch_last_success_age_seconds"
  dimensions          = { batch = each.key }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = each.value
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# ---- ダッシュボード ----------------------------------------------------------

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "${local.name_prefix}-dashboard"

  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6,
        properties = {
          title  = "API リクエスト / 5xx",
          region = var.aws_region,
          view   = "timeSeries",
          stat   = "Sum",
          period = 300,
          metrics = [
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", aws_lb.main.arn_suffix],
            ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", aws_lb.main.arn_suffix],
            ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", aws_lb.main.arn_suffix],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6,
        properties = {
          title  = "API レイテンシ (p95/p99)",
          region = var.aws_region,
          view   = "timeSeries",
          period = 300,
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", aws_lb.main.arn_suffix, { stat = "p95" }],
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", aws_lb.main.arn_suffix, { stat = "p99" }],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6,
        properties = {
          title  = "ECS CPU/メモリ",
          region = var.aws_region,
          view   = "timeSeries",
          stat   = "Average",
          period = 300,
          metrics = [
            ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", aws_ecs_service.backend.name],
            ["AWS/ECS", "MemoryUtilization", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", aws_ecs_service.backend.name],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6,
        properties = {
          title  = "RDS CPU / 接続数",
          region = var.aws_region,
          view   = "timeSeries",
          stat   = "Average",
          period = 300,
          metrics = [
            ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", aws_db_instance.main.identifier],
            ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", aws_db_instance.main.identifier],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6,
        properties = {
          title  = "ビジネス滞留（Outbox/通知/返金）",
          region = var.aws_region,
          view   = "timeSeries",
          stat   = "Maximum",
          period = 300,
          metrics = [
            [var.metrics_namespace, "cf_outbox_pending_count"],
            [var.metrics_namespace, "cf_notification_pending_count"],
            [var.metrics_namespace, "cf_refund_retry_wait_count"],
            [var.metrics_namespace, "cf_refund_failed_count"],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 12, width = 12, height = 6,
        properties = {
          title  = "通知送信レート（結果別）",
          region = var.aws_region,
          view   = "timeSeries",
          stat   = "Sum",
          period = 300,
          metrics = [
            [var.metrics_namespace, "cf_notification_delivery_total", "result", "SUCCESS"],
            [var.metrics_namespace, "cf_notification_delivery_total", "result", "FAILURE"],
          ]
        }
      },
    ]
  })
}
