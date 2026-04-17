# GitHub Actions OIDC Provider
# 이미 존재할 경우: terraform import aws_iam_openid_connect_provider.github_actions arn:aws:iam::631124976154:oidc-provider/token.actions.githubusercontent.com
resource "aws_iam_openid_connect_provider" "github_actions" {
  url = "https://token.actions.githubusercontent.com"

  client_id_list = ["sts.amazonaws.com"]

  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

# GitHub Actions 배포 역할 (기존 역할 Trust Policy 업데이트)
# 이미 존재할 경우: terraform import aws_iam_role.github_actions github-actions-campaign-deploy-role
resource "aws_iam_role" "github_actions" {
  name = "github-actions-campaign-deploy-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Federated = "arn:aws:iam::${var.account_id}:oidc-provider/token.actions.githubusercontent.com"
        }
        Action = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringLike = {
            "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:*"
          }
          StringEquals = {
            "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          }
        }
      }
    ]
  })
}

# 기존 정책 연결 유지
resource "aws_iam_role_policy_attachment" "github_actions_ecr" {
  role       = aws_iam_role.github_actions.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryFullAccess"
}

resource "aws_iam_policy" "github_actions_s3_minimal" {
  name = "GitHubActions-S3-Deploy-campaign"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          "arn:aws:s3:::campaign-deploy-${var.account_id}",
          "arn:aws:s3:::campaign-deploy-${var.account_id}/*"
        ]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "github_actions_s3" {
  role       = aws_iam_role.github_actions.name
  policy_arn = aws_iam_policy.github_actions_s3_minimal.arn
}

resource "aws_iam_role_policy_attachment" "github_actions_codedeploy" {
  role       = aws_iam_role.github_actions.name
  policy_arn = "arn:aws:iam::aws:policy/AWSCodeDeployFullAccess"
}

resource "aws_iam_policy" "github_actions_ssm_minimal" {
  name = "GitHubActions-SSM-Read-campaign"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ssm:GetParameter",
          "ssm:GetParameters",
          "ssm:GetParametersByPath"
        ]
        Resource = "arn:aws:ssm:${var.region}:${var.account_id}:parameter/batch-kafka/prod/*"
      },
      {
        Effect   = "Allow"
        Action   = ["ssm:PutParameter"]
        Resource = "arn:aws:ssm:${var.region}:${var.account_id}:parameter/batch-kafka/prod/ECR_IMAGE"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "github_actions_ssm" {
  role       = aws_iam_role.github_actions.name
  policy_arn = aws_iam_policy.github_actions_ssm_minimal.arn
}
