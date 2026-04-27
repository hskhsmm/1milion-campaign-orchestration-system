# ──────────────────────────────────────────
# CodeDeploy IAM Role (서비스 롤)
# ──────────────────────────────────────────
resource "aws_iam_role" "codedeploy" {
  name = "CodeDeployServiceRole"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "codedeploy.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "codedeploy" {
  role       = aws_iam_role.codedeploy.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSCodeDeployRole"
}

# ──────────────────────────────────────────
# CodeDeploy Application
# import: terraform import aws_codedeploy_app.app batch-kafka-app
# ──────────────────────────────────────────
resource "aws_codedeploy_app" "app" {
  name             = "batch-kafka-app"
  compute_platform = "Server"
}

# ──────────────────────────────────────────
# CodeDeploy Deployment Group
# import: terraform import aws_codedeploy_deployment_group.app batch-kafka-app:batch-kafka-prod-dg
# ──────────────────────────────────────────
resource "aws_codedeploy_deployment_group" "app" {
  app_name               = aws_codedeploy_app.app.name
  deployment_group_name  = "batch-kafka-prod-dg"
  service_role_arn       = aws_iam_role.codedeploy.arn

  deployment_config_name = "CodeDeployDefault.OneAtATime"

  autoscaling_groups = [aws_autoscaling_group.app.name]

  deployment_style {
    deployment_option = "WITHOUT_TRAFFIC_CONTROL"
    deployment_type   = "IN_PLACE"
  }

  auto_rollback_configuration {
    enabled = true
    events  = ["DEPLOYMENT_FAILURE"]
  }
}
