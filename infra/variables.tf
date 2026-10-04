variable "region" {
  description = "AWS region"
  type        = string
  default     = "ap-northeast-2"
}

variable "account_id" {
  description = "AWS Account ID"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "subnet_id" {
  description = "Subnet ID for terraform-mcp EC2"
  type        = string
}

variable "github_repo" {
  description = "GitHub repository (owner/repo)"
  type        = string
  default     = "hskhsmm/1milion-campaign-orchestration-system"
}

variable "app_ami_id" {
  description = "앱 ASG Launch Template AMI (Docker + CodeDeploy agent). 잔존 컨테이너 없는 AMI로 교체 시 ops/scripts/rebuild-app-ami.sh 결과로 변경"
  type        = string
  default     = "ami-04256089838fb06c8" # 2026-10-04 재생성, 격리 부팅에서 컨테이너·이미지 0개 검증
}
