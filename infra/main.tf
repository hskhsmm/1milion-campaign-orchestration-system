terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket = "campaign-terraform-state-631124976154"
    key    = "infrastructure/terraform.tfstate"
    region = "ap-northeast-2"
  }
}

provider "aws" {
  region = var.region
}
