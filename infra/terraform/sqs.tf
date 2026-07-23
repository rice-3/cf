# Outbox配送のSQS化（要判断B / ADR候補、基本設計 §8.1 BAT-006）。
# マルチインスタンス時に Transactional Outbox の配送先をSQSへ切り替えるためのキュー。
# 現行アプリはアプリ内配送（InProcessOutboxDispatcher）だが、SQSアダプタ導入時にこれを参照する。

resource "aws_sqs_queue" "outbox_dlq" {
  name                      = "${local.name_prefix}-outbox-dlq"
  message_retention_seconds = 1209600 # 14日
  sqs_managed_sse_enabled   = true
  tags                      = { Name = "${local.name_prefix}-outbox-dlq" }
}

resource "aws_sqs_queue" "outbox" {
  name                       = "${local.name_prefix}-outbox"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 345600 # 4日
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.outbox_dlq.arn
    maxReceiveCount     = 5 # Outboxの再試行上限（詳細設計 §9.2）に合わせる
  })

  tags = { Name = "${local.name_prefix}-outbox" }
}
