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

  # 상세 모니터링: EC2 CPU를 1분 단위로 발행 (기본 5분 → 알람 평가가 수 분 지연됨)
  monitoring {
    enabled = true
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
  default_instance_warmup   = 150 # 부팅+배포 약 2분 반 동안 신규 인스턴스 CPU를 평균에서 제외
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

# ──────────────────────────────────────────
# Step Scaling (CPU 80% 2분 연속 → +1)
# ──────────────────────────────────────────
# Target Tracking 알람은 1분 데이터 3개 연속(실측 감지 약 5분 47초)으로 고정이라 순간 폭주를 못 따라간다.
# 1분 평균 80% 이상이 2번 연속이면 일시 피크가 아닌 지속 부하로 보고 즉시 1대 증설한다.
resource "aws_autoscaling_policy" "cpu_spike_step" {
  name                    = "batch-kafka-app-cpu-spike-step"
  autoscaling_group_name  = aws_autoscaling_group.app.name
  policy_type             = "StepScaling"
  adjustment_type         = "ChangeInCapacity"
  metric_aggregation_type = "Average"

  step_adjustment {
    scaling_adjustment          = 1
    metric_interval_lower_bound = 0
  }
}

resource "aws_cloudwatch_metric_alarm" "app_cpu_spike" {
  alarm_name          = "batch-kafka-app-cpu-spike"
  alarm_description   = "앱 ASG 평균 CPU 80% 이상 2분 연속 → Step Scaling +1"
  namespace           = "AWS/EC2"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  threshold           = 80
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "missing"

  dimensions = {
    AutoScalingGroupName = aws_autoscaling_group.app.name
  }

  alarm_actions = [aws_autoscaling_policy.cpu_spike_step.arn]
}
