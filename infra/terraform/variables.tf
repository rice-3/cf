variable "aws_region" {
  description = "デプロイ先AWSリージョン"
  type        = string
  default     = "ap-northeast-1"
}

variable "project" {
  description = "システム略称（リソース名接頭辞、基本設計 §12.4）"
  type        = string
  default     = "cftraining"
}

variable "environment" {
  description = "環境区分（dev / staging / production）"
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "staging", "production"], var.environment)
    error_message = "environment は dev / staging / production のいずれか。"
  }
}

variable "vpc_cidr" {
  description = "VPCのCIDR"
  type        = string
  default     = "10.20.0.0/16"
}

variable "az_count" {
  description = "使用するAZ数（Multi-AZ、基本設計 §12.2）"
  type        = number
  default     = 2
}

variable "container_port" {
  description = "アプリのコンテナポート（Spring Boot）"
  type        = number
  default     = 8080
}

variable "desired_count" {
  description = "ECSサービスの希望タスク数"
  type        = number
  default     = 2
}

variable "task_cpu" {
  description = "Fargateタスクの CPU ユニット"
  type        = string
  default     = "512"
}

variable "task_memory" {
  description = "Fargateタスクのメモリ(MiB)"
  type        = string
  default     = "1024"
}

variable "db_instance_class" {
  description = "RDSインスタンスクラス"
  type        = string
  default     = "db.t4g.micro"
}

variable "db_name" {
  description = "アプリDB名"
  type        = string
  default     = "cf"
}

variable "db_username" {
  description = "RDSマスターユーザー名"
  type        = string
  default     = "cf_app"
}

variable "cognito_issuer" {
  description = "Cognito発行者URI（Resource Server用、§13.1）。未確定なら空でよい"
  type        = string
  default     = ""
}

variable "github_repository" {
  description = "CDのOIDC信頼対象 GitHubリポジトリ（owner/repo）"
  type        = string
  default     = "rice-3/cf"
}

variable "image_tag" {
  description = "初期タスク定義が参照するイメージタグ（以後はCDが更新）"
  type        = string
  default     = "bootstrap"
}
