# ALB 보안그룹 - 인터넷에서 80, 443 허용
resource "aws_security_group" "alb_public" {
  name        = "alb-public"
  description = "alb sg"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {}
}

# 앱 보안그룹 - ALB에서 8080 허용
resource "aws_security_group" "app" {
  name        = "app-sg"
  description = "Application EC2 security group"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb_public.id]
  }

  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.terraform_mcp.id]
    description     = "Prometheus scrape spring-boot metrics"
  }

  ingress {
    from_port       = 9121
    to_port         = 9121
    protocol        = "tcp"
    security_groups = [aws_security_group.terraform_mcp.id]
    description     = "redis-exporter scrape from terraform-mcp"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {}
}

# RDS 보안그룹 - app-sg에서 3306 허용
resource "aws_security_group" "rds_mysql" {
  name        = "rds-mysql"
  description = "RDS MySQL for batch-kafka"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {}
}

# Kafka 보안그룹 - app/monitoring은 9092만, broker/controller 내부 통신은 self 허용
resource "aws_security_group" "kafka" {
  name        = "Kafka-SG"
  description = "launch-wizard-1 created 2025-12-27T06:24:22.804Z"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 9092
    to_port         = 9092
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  ingress {
    from_port       = 9092
    to_port         = 9092
    protocol        = "tcp"
    security_groups = [aws_security_group.terraform_mcp.id]
    description     = "kafka-exporter scrape from terraform-mcp"
  }

  ingress {
    from_port   = 9092
    to_port     = 9092
    protocol    = "tcp"
    self        = true
    description = "Broker internal communication"
  }

  ingress {
    from_port   = 9094
    to_port     = 9094
    protocol    = "tcp"
    self        = true
    description = "Controller quorum communication"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {}
}

# ElastiCache 보안그룹 - app-sg에서 6379 허용, 아웃바운드 없음
resource "aws_security_group" "elasticache_redis" {
  name        = "elasticache-redis"
  description = "el"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.terraform_mcp.id]
    description     = "redis-exporter scrape from terraform-mcp"
  }

  tags = {}
}
