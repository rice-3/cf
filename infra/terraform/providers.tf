provider "aws" {
  region = var.aws_region

  default_tags {
    # 全リソース共通タグ（基本設計 §12.4）
    tags = {
      System      = var.project
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}
