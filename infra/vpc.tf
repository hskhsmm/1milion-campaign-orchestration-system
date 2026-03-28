# VPC
resource "aws_vpc" "main" {
  cidr_block           = "172.31.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {}
}

# Internet Gateway
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {}
}

# Route Table (Main)
resource "aws_route_table" "main" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {}
}

# 퍼블릭 서브넷 2a
resource "aws_subnet" "public_2a" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "172.31.0.0/20"
  availability_zone       = "ap-northeast-2a"
  map_public_ip_on_launch = true

  tags = {}
}

# 퍼블릭 서브넷 2b
resource "aws_subnet" "public_2b" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "172.31.16.0/20"
  availability_zone       = "ap-northeast-2b"
  map_public_ip_on_launch = true

  tags = {}
}

# 퍼블릭 서브넷 2c
resource "aws_subnet" "public_2c" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "172.31.32.0/20"
  availability_zone       = "ap-northeast-2c"
  map_public_ip_on_launch = true

  tags = {}
}

# 퍼블릭 서브넷 2d
resource "aws_subnet" "public_2d" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "172.31.48.0/20"
  availability_zone       = "ap-northeast-2d"
  map_public_ip_on_launch = true

  tags = {}
}

# 앱 서브넷 (이름만 Private, 실제론 IGW 연결된 Public)
# v2에서 NAT Gateway 추가 후 진짜 Private으로 전환 예정
resource "aws_subnet" "private_app_2a" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "172.31.100.0/24"
  availability_zone       = "ap-northeast-2a"
  map_public_ip_on_launch = false

  tags = {
    Name = "private-app-subnet-1"
  }
}
