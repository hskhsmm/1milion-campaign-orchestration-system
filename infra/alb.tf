# 기존 단일 리소스를 count 인스턴스로 주소만 옮긴다. alb_enabled=true 최초 plan은 교체가 없어야 한다.
moved {
  from = aws_lb.main
  to   = aws_lb.main[0]
}

moved {
  from = aws_lb_listener.http
  to   = aws_lb_listener.http[0]
}

# ALB와 Listener만 비용 절감 시 제거한다.
resource "aws_lb" "main" {
  count              = var.alb_enabled ? 1 : 0
  name               = "alb-batch-kafka-api"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb_public.id]
  subnets = [
    aws_subnet.public_2a.id,
    aws_subnet.public_2b.id,
  ]

  ip_address_type = "ipv4"

  tags = {}
}

# Target Group은 ASG가 ARN으로 참조하므로 ALB가 내려가도 유지한다.
resource "aws_lb_target_group" "api_8080" {
  name        = "tg-api-8080"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "instance"

  health_check {
    enabled             = true
    path                = "/actuator/health"
    protocol            = "HTTP"
    port                = "traffic-port"
    interval            = 10 # 헬스 체크 간격. 신규 타깃은 첫 성공 체크 후 트래픽 대상이 되며, unhealthy 타깃 복구에는 연속 성공 체크 2회가 필요
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 5 # unhealthy 판정 약 50초 유지 (기존 60초) — 고부하 시 오탐 교체 방지
    matcher             = "200"
  }

  tags = {}
}

# Listener (HTTP:80 → tg-api-8080)
resource "aws_lb_listener" "http" {
  count             = var.alb_enabled ? 1 : 0
  load_balancer_arn = aws_lb.main[0].arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api_8080.arn
  }

  lifecycle {
    ignore_changes = [default_action]
  }
}
