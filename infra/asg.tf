# ──────────────────────────────────────────
# Launch Template
# ──────────────────────────────────────────
resource "aws_launch_template" "app" {
  name          = "batch-kafka-app-lt"
  image_id      = "ami-01c64e7a84a57e681"  # batch-kafka-app-ami (Docker + CodeDeploy 포함)
  instance_type = "t3.small"

  iam_instance_profile {
    name = aws_iam_instance_profile.batch_kafka_app.name
  }

  network_interfaces {
    associate_public_ip_address = true
    security_groups             = [aws_security_group.app.id]
  }

  tag_specifications {
    resource_type = "instance"
    tags = {
      Name = "batch-kafka-app"
    }
  }
}

# ──────────────────────────────────────────
# Auto Scaling Group
# ──────────────────────────────────────────
resource "aws_autoscaling_group" "app" {
  name                = "batch-kafka-app-asg"
  min_size            = 2
  max_size            = 3
  desired_capacity    = 2
  vpc_zone_identifier = [
    aws_subnet.private_app_2a.id,
    aws_subnet.private_app_2b.id,
  ]

  launch_template {
    id      = aws_launch_template.app.id
    version = "$Latest"
  }

  target_group_arns = [aws_lb_target_group.api_8080.arn]

  health_check_type         = "ELB"
  health_check_grace_period = 180  # 앱 기동 시간 여유 (초)

  tag {
    key                 = "Name"
    value               = "batch-kafka-app"
    propagate_at_launch = true
  }

  lifecycle {
    ignore_changes = [desired_capacity, min_size, max_size]  # 수동 스케일 조정 보호
  }
}

# ──────────────────────────────────────────
# Target Tracking Scaling Policy (CPU 60%)
# ──────────────────────────────────────────
resource "aws_autoscaling_policy" "cpu_target_tracking" {
  name                   = "batch-kafka-app-cpu-tracking"
  autoscaling_group_name = aws_autoscaling_group.app.name
  policy_type            = "TargetTrackingScaling"

  target_tracking_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ASGAverageCPUUtilization"
    }
    target_value     = 60.0
    disable_scale_in = true  # 스케일인 비활성화 — 수동으로만 축소
  }
}
