# ──────────────────────────────────────────
# Launch Template
# ──────────────────────────────────────────
# user-data = boothook(docker 기동 전 AMI 잔존 컨테이너 restart 정책 해제) + 기존 셸 스크립트.
# boothook은 shell script 파트보다 먼저, docker.service 기동 전에 실행된다.
locals {
  app_user_data = <<EOT
Content-Type: multipart/mixed; boundary="==BATCH-KAFKA-APP=="
MIME-Version: 1.0

--==BATCH-KAFKA-APP==
Content-Type: text/cloud-boothook; charset="us-ascii"

${file("${path.module}/app-boothook.sh")}
--==BATCH-KAFKA-APP==
Content-Type: text/x-shellscript; charset="us-ascii"

${file("${path.module}/app-user-data.sh")}
--==BATCH-KAFKA-APP==--
EOT
}

resource "aws_launch_template" "app" {
  name          = "batch-kafka-app-lt"
  image_id      = var.app_ami_id
  instance_type = "t3.small"
  user_data     = base64encode(local.app_user_data)

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
  name             = "batch-kafka-app-asg"
  min_size         = 2
  max_size         = 3
  desired_capacity = 2
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
  health_check_grace_period = 180 # 앱 기동 시간 여유 (초)
  metrics_granularity       = "1Minute"
  enabled_metrics = [
    "GroupAndWarmPoolDesiredCapacity",
    "GroupAndWarmPoolTotalCapacity",
    "GroupDesiredCapacity",
    "GroupInServiceCapacity",
    "GroupInServiceInstances",
    "GroupMaxSize",
    "GroupMinSize",
    "GroupPendingCapacity",
    "GroupPendingInstances",
    "GroupStandbyCapacity",
    "GroupStandbyInstances",
    "GroupTerminatingCapacity",
    "GroupTerminatingInstances",
    "GroupTerminatingRetainedCapacity",
    "GroupTerminatingRetainedInstances",
    "GroupTotalCapacity",
    "GroupTotalInstances",
    "WarmPoolDesiredCapacity",
    "WarmPoolMinSize",
    "WarmPoolPendingCapacity",
    "WarmPoolPendingRetainedCapacity",
    "WarmPoolTerminatingCapacity",
    "WarmPoolTerminatingRetainedCapacity",
    "WarmPoolTotalCapacity",
    "WarmPoolWarmedCapacity",
  ]

  tag {
    key                 = "Name"
    value               = "batch-kafka-app"
    propagate_at_launch = true
  }

  lifecycle {
    ignore_changes = [desired_capacity, min_size, max_size] # 수동 스케일 조정 보호
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
    disable_scale_in = true # 스케일인 비활성화 — 수동으로만 축소
  }
}
