resource "aws_lb" "main" {
  name               = "${local.name_prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  tags = { Name = "${local.name_prefix}-alb" }
}

resource "aws_lb_target_group" "backend" {
  name        = "${local.name_prefix}-tg"
  port        = var.container_port
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip" # Fargate（awsvpc）

  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = { Name = "${local.name_prefix}-tg" }
}

# HTTP:80。HTTPS有効時（domain_name指定時）は 80→443 リダイレクト、無効時は直接forward。
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  dynamic "default_action" {
    for_each = local.enable_https ? [1] : []
    content {
      type = "redirect"
      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }

  dynamic "default_action" {
    for_each = local.enable_https ? [] : [1]
    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.backend.arn
    }
  }
}

# HTTPS:443（domain_name指定時のみ）。ACM証明書でTLS終端しforwardする。
resource "aws_lb_listener" "https" {
  count             = local.enable_https ? 1 : 0
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate.main[0].arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
}

# 内部向けパスの外部遮断（要判断H）。
#
# 実際に転送を行うリスナーへ付ける。HTTPS有効時のHTTP:80は443へリダイレクトするだけなので
# ルールは不要（リダイレクト先の443側で評価される）。
#
# ヘルスチェックへの影響は無い: ターゲットグループのヘルスチェックはロードバランサーノードから
# ターゲットへ直接送られ、リスナールールを経由しない。
locals {
  serving_listener_arn = local.enable_https ? aws_lb_listener.https[0].arn : aws_lb_listener.http.arn
}

# actuator は全環境で外部に出さない。
# Prometheusメトリクスは同一タスク内のCollectorサイドカーが localhost から取得する構成のため
# （§2.1）、外部公開を止めてもメトリクスパイプラインは動く。
resource "aws_lb_listener_rule" "block_actuator" {
  listener_arn = local.serving_listener_arn
  priority     = 100

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Not Found"
      status_code  = "404"
    }
  }

  condition {
    path_pattern {
      values = ["/actuator", "/actuator/*"]
    }
  }
}

# Swagger UI / OpenAPI spec は dev でのみ外部から参照できるようにする（要判断H）。
# staging / production ではこのルールに加えアプリ側でも springdoc を無効化する（ecs.tf）。
resource "aws_lb_listener_rule" "block_api_docs" {
  count        = var.environment == "dev" ? 0 : 1
  listener_arn = local.serving_listener_arn
  priority     = 110

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Not Found"
      status_code  = "404"
    }
  }

  condition {
    path_pattern {
      values = ["/swagger-ui/*", "/swagger-ui.html", "/v3/api-docs*"]
    }
  }
}
