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

variable "enable_ecs_exec" {
  description = "ECS Exec（SSM経由のセッション/ポートフォワード）を有効化するか。Private配置のRDSへの保守経路として使う。操作は監査ログへ記録される。未指定なら environment から決まる（production は false、それ以外は true）。production で保守のため一時的に開ける場合だけ明示的に true を渡し、作業後に戻す。"
  type        = bool
  default     = null
}

variable "audit_archive_retention_days" {
  description = "監査アーカイブ（BAT-009）のS3側保持日数。DBの保持期限を超えた行がアーカイブされるため、これはDB保持期間への上乗せ分（基本設計 §7.7: 監査ログ3年 / AI利用記録1年）。既定365日で、監査ログは実質4年・AI利用記録は実質2年になる。"
  type        = number
  default     = 365
}

variable "audit_archive_lock_days" {
  description = "監査アーカイブへ S3 Object Lock の既定保持を設定する日数。0 で無効（既定）。**COMPLIANCE で有効にすると期間中はバケットを空にできず `terraform destroy` が失敗する**（ADR-0009）。都度 apply/destroy する環境では 0 のままにする。"
  type        = number
  default     = 0

  validation {
    condition     = var.audit_archive_lock_days >= 0
    error_message = "audit_archive_lock_days は 0 以上で指定してください。"
  }
}

variable "audit_archive_lock_mode" {
  description = "Object Lock のモード。GOVERNANCE は `s3:BypassGovernanceRetention` を持つ主体が解除できる。COMPLIANCE はルートでも解除できない。"
  type        = string
  default     = "GOVERNANCE"

  validation {
    condition     = contains(["GOVERNANCE", "COMPLIANCE"], var.audit_archive_lock_mode)
    error_message = "audit_archive_lock_mode は GOVERNANCE または COMPLIANCE を指定してください。"
  }
}

variable "health_check_grace_period_seconds" {
  description = "ECSサービスがALBヘルスチェックを無視する起動猶予（秒）。Spring Boot起動 + Flyway移行の所要時間を見込む。"
  type        = number
  default     = 180
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

# ---- 追加リソース（§2.1 未カバーAWSリソース）用 ------------------------------

variable "domain_name" {
  description = "アプリのFQDN（例: cf.example.com）。設定するとACM証明書とHTTPS(443)を有効化する。空ならHTTP(80)のみ。"
  type        = string
  default     = ""
}

variable "route53_zone_id" {
  description = "ACMのDNS検証レコードを作成するRoute53ホストゾーンID。空なら検証レコードは手動作成（validation_optionsを出力）。"
  type        = string
  default     = ""
}

variable "ses_domain" {
  description = "SES送信ドメイン（例: example.com）。設定するとSESドメインID・DKIMを作成する。空なら作成しない。"
  type        = string
  default     = ""
}

variable "ses_from_address" {
  description = "通知メールの送信元（CF_SES_FROM_ADDRESS）。空なら ses_domain から no-reply@<domain> を導出する。両方空ならアプリ既定と同じ無効ドメインになり SES送信は失敗する。"
  type        = string
  default     = ""
}

variable "enable_waf" {
  description = "ALBへWAF(WebACL)を関連付けるか（AWSマネージドルール + レート制限）。"
  type        = bool
  default     = true
}

variable "waf_rate_limit" {
  description = "WAFレートベースルールの5分あたり上限リクエスト数（1IP）。"
  type        = number
  default     = 2000
}

variable "cognito_callback_urls" {
  description = "Cognito App Client のコールバックURL（OIDC）。"
  type        = list(string)
  default     = ["http://localhost:3000/api/auth/callback/cognito"]
}

variable "cognito_logout_urls" {
  description = "Cognito App Client のログアウトURL。"
  type        = list(string)
  default     = ["http://localhost:3000"]
}

# ---- 監視・アラート（§2.1、閾値は docs/ops/monitoring.md） --------------------

variable "alert_email" {
  description = "アラート通知先メール（SNS購読）。空なら購読は作成しない（トピックのみ）。"
  type        = string
  default     = ""
}

variable "metrics_namespace" {
  description = "ビジネス/バッチメトリクスのCloudWatch名前空間（Prometheus→CloudWatchパイプラインの発行先）。"
  type        = string
  default     = "CF/Training"
}

variable "api_p95_latency_threshold_seconds" {
  description = "API p95 レイテンシ（ALB TargetResponseTime）のCriticalアラート閾値（秒）。"
  type        = number
  default     = 1
}
