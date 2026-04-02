# ALB
resource "aws_lb" "main" {
  name               = "alb-batch-kafka-api"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb_public.id]
  subnets            = [
    aws_subnet.public_2a.id,
    aws_subnet.public_2b.id,
  ]

  ip_address_type = "ipv4"

  tags = {}
}

# Target Group
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
    interval            = 30
    timeout             = 5
    healthy_threshold   = 5
    unhealthy_threshold = 2
    matcher             = "200"
  }

  tags = {}
}

# Listener (HTTP:80 → tg-api-8080)
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
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
