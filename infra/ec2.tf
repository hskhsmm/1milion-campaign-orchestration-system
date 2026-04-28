# terraform-mcp EC2 IAM Role (SSM 접속용)
resource "aws_iam_role" "terraform_mcp" {
  name = "terraform-mcp-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_instance_profile" "terraform_mcp" {
  name = "terraform-mcp-profile"
  role = aws_iam_role.terraform_mcp.name
}

resource "aws_iam_role_policy_attachment" "terraform_mcp_ssm" {
  role       = aws_iam_role.terraform_mcp.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# Prometheus EC2 Service Discovery용 — ASG 인스턴스 동적 감지
resource "aws_iam_role_policy" "terraform_mcp_ec2_describe" {
  name = "EC2Describe-for-prometheus"
  role = aws_iam_role.terraform_mcp.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ec2:DescribeInstances"]
      Resource = "*"
    }]
  })
}

# terraform-mcp 보안 그룹
resource "aws_security_group" "terraform_mcp" {
  name        = "terraform-mcp-sg"
  description = "Security group for terraform-mcp server"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 8000
    to_port     = 8000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "FastAPI MCP server"
  }

  ingress {
    from_port   = 9090
    to_port     = 9090
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Prometheus UI"
  }

  ingress {
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Grafana UI"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "terraform-mcp-sg"
  }
}

# ──────────────────────────────────────────
# batch-kafka-app / kafka-1 공통 IAM Role
# ──────────────────────────────────────────

resource "aws_iam_role" "batch_kafka_app" {
  name        = "ec2-batchkafka-role"
  description = "Allows EC2 instances to call AWS services on your behalf."

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_instance_profile" "batch_kafka_app" {
  name = "ec2-batchkafka-role"
  role = aws_iam_role.batch_kafka_app.name
}

# 커스텀 정책 - SSM Parameter Store 읽기
resource "aws_iam_policy" "ssm_parameter_read" {
  name = "SSMParameterRead-batch-kafka-prod"

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
        Resource = "arn:aws:ssm:*:*:parameter/batch-kafka/prod/*"
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = "*"
      }
    ]
  })
}

# AWS 관리형 정책 연결
resource "aws_iam_role_policy_attachment" "batch_kafka_cloudwatch" {
  role       = aws_iam_role.batch_kafka_app.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

resource "aws_iam_role_policy_attachment" "batch_kafka_ssm" {
  role       = aws_iam_role.batch_kafka_app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "batch_kafka_codedeploy_ec2" {
  role       = aws_iam_role.batch_kafka_app.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2RoleforAWSCodeDeploy"
}

resource "aws_iam_role_policy_attachment" "batch_kafka_ecr_read" {
  role       = aws_iam_role.batch_kafka_app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

resource "aws_iam_role_policy_attachment" "batch_kafka_codedeploy_role" {
  role       = aws_iam_role.batch_kafka_app.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSCodeDeployRole"
}

resource "aws_iam_role_policy_attachment" "batch_kafka_ssm_parameter_read" {
  role       = aws_iam_role.batch_kafka_app.name
  policy_arn = aws_iam_policy.ssm_parameter_read.arn
}

# batch-kafka-app EC2는 ASG(asg.tf)로 대체됨

# ──────────────────────────────────────────
# kafka-1 EC2
# ──────────────────────────────────────────

resource "aws_instance" "kafka_1" {
  ami                         = "ami-0b818a04bc9c2133c"
  instance_type               = "t3.small"
  subnet_id                   = aws_subnet.public_2a.id
  vpc_security_group_ids      = [aws_security_group.kafka.id]
  iam_instance_profile        = aws_iam_instance_profile.batch_kafka_app.name
  associate_public_ip_address = true
  private_ip                  = "172.31.5.164"

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    encrypted   = false
  }

  tags = {
    Name = "kafka-1"
  }

  lifecycle {
    ignore_changes = [associate_public_ip_address]
  }
}

resource "aws_instance" "kafka_2" {
  ami                         = "ami-0b818a04bc9c2133c"
  instance_type               = "t3.small"
  subnet_id                   = aws_subnet.public_2b.id
  vpc_security_group_ids      = [aws_security_group.kafka.id]
  iam_instance_profile        = aws_iam_instance_profile.batch_kafka_app.name
  associate_public_ip_address = true
  private_ip                  = "172.31.16.164"

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    encrypted   = false
  }

  tags = {
    Name = "kafka-2"
  }

  lifecycle {
    ignore_changes = [associate_public_ip_address]
  }
}

resource "aws_instance" "kafka_3" {
  ami                         = "ami-0b818a04bc9c2133c"
  instance_type               = "t3.small"
  subnet_id                   = aws_subnet.public_2c.id
  vpc_security_group_ids      = [aws_security_group.kafka.id]
  iam_instance_profile        = aws_iam_instance_profile.batch_kafka_app.name
  associate_public_ip_address = true
  private_ip                  = "172.31.32.164"

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    encrypted   = false
  }

  tags = {
    Name = "kafka-3"
  }

  lifecycle {
    ignore_changes = [associate_public_ip_address]
  }
}

# terraform-mcp EC2 인스턴스
resource "aws_instance" "terraform_mcp" {
  ami                    = "ami-0ecfdfd1c8ae01aec" # Amazon Linux 2023 ap-northeast-2 (2026-03-27)
  instance_type          = "t3.large"
  subnet_id              = aws_subnet.public_2a.id
  vpc_security_group_ids = [aws_security_group.terraform_mcp.id]
  iam_instance_profile   = aws_iam_instance_profile.terraform_mcp.name

  associate_public_ip_address = true

  root_block_device {
    volume_type = "gp3"
    volume_size = 20
    encrypted   = true
  }

  metadata_options {
    http_tokens                 = "required" # IMDSv2 강제
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 2
  }

  user_data = filebase64("${path.module}/user-data.sh")

  tags = {
    Name    = "terraform-mcp"
    Purpose = "MCP-Server"
  }

  lifecycle {
    ignore_changes = [associate_public_ip_address]
  }
}
