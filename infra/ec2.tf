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

# terraform-mcp EC2 인스턴스
resource "aws_instance" "terraform_mcp" {
  ami                    = "ami-0ecfdfd1c8ae01aec" # Amazon Linux 2023 ap-northeast-2 (2026-03-27)
  instance_type          = "t3.small"
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.terraform_mcp.id]
  iam_instance_profile   = aws_iam_instance_profile.terraform_mcp.name

  associate_public_ip_address = false

  root_block_device {
    volume_type = "gp3"
    volume_size = 20
    encrypted   = true
  }

  metadata_options {
    http_tokens                 = "required" # IMDSv2 강제
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 1
  }

  user_data = filebase64("${path.module}/user-data.sh")

  tags = {
    Name    = "terraform-mcp"
    Purpose = "MCP-Server"
  }
}
