# ファイル保存バケット（メイン画像等、基本設計 §10.2 / 詳細設計 §8.19）。
# アプリは presigned URL でPUT/GETする。公開アクセスは全面ブロックする。
resource "aws_s3_bucket" "files" {
  bucket = local.s3_file_bucket
  tags   = { Name = local.s3_file_bucket }
}

resource "aws_s3_bucket_public_access_block" "files" {
  bucket                  = aws_s3_bucket.files.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "files" {
  bucket = aws_s3_bucket.files.id
  rule {
    object_ownership = "BucketOwnerEnforced" # ACL無効化（所有者強制）
  }
}

resource "aws_s3_bucket_versioning" "files" {
  bucket = aws_s3_bucket.files.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "files" {
  bucket = aws_s3_bucket.files.id
  rule {
    # SSE-S3（AES256）。タスクロールに追加のKMS権限が不要で、presigned PUT/GETがそのまま機能する。
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # 未完了アップロードの一時キー（pending/）は短期失効。実体削除はBAT-008も担う。
  rule {
    id     = "expire-pending-uploads"
    status = "Enabled"
    filter {
      prefix = "pending/"
    }
    expiration {
      days = 1
    }
  }
}

# ブラウザからの presigned PUT を許可するCORS（オリジンは要件に応じて絞る）。
resource "aws_s3_bucket_cors_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  cors_rule {
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_origins = local.enable_https ? ["https://${var.domain_name}"] : ["*"]
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}
