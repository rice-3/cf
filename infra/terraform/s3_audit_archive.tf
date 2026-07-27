# 監査アーカイブ用バケット（BAT-009、基本設計 §7.7「監査ログ3年・改ざん防止・参照権限限定」、ADR-0009）。
#
# ファイル用バケット（s3.tf）とは分離する。あちらはブラウザからの presigned PUT を受けるため
# CORS を開けており、キーは presign 経路から到達しうる。監査アーカイブは書き込み専用・
# 参照権限限定で運用するため、同じバケットに置かない。

resource "aws_s3_bucket" "audit_archive" {
  bucket = local.s3_audit_archive_bucket

  # Object Lock は**バケット作成時にしか有効化できない**（後付けは不可）。
  # 既定の保持ルールは置かないため、この時点では何もロックされない。
  # production で改ざん防止を強制する段階になったら
  # audit_archive_lock_days を指定するだけで済むようにしておく。
  object_lock_enabled = true

  tags = { Name = local.s3_audit_archive_bucket }
}

resource "aws_s3_bucket_public_access_block" "audit_archive" {
  bucket                  = aws_s3_bucket.audit_archive.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "audit_archive" {
  bucket = aws_s3_bucket.audit_archive.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Object Lock の前提。上書きされても直前の版が残る（改ざんの検知・復旧に使う）。
resource "aws_s3_bucket_versioning" "audit_archive" {
  bucket = aws_s3_bucket.audit_archive.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "audit_archive" {
  bucket = aws_s3_bucket.audit_archive.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 既定の保持期間（改ざん防止の強制）。0 なら設定しない。
#
# **COMPLIANCE は誰も（ルートでも）解除できないため、指定日数が経つまで
# バケットを空にできず `terraform destroy` が失敗する。** 都度 apply/destroy で
# 運用する dev では致命的なので、既定では有効化しない（ADR-0009）。
resource "aws_s3_bucket_object_lock_configuration" "audit_archive" {
  count  = var.audit_archive_lock_days > 0 ? 1 : 0
  bucket = aws_s3_bucket.audit_archive.id

  rule {
    default_retention {
      mode = var.audit_archive_lock_mode
      days = var.audit_archive_lock_days
    }
  }
}

# 保持期間（§7.7）。DBの保持期限を超えた行がここへ来るので、
# ここでの日数は「DB保持期間に上乗せする分」である（audit_log なら 3年 + 本設定）。
resource "aws_s3_bucket_lifecycle_configuration" "audit_archive" {
  bucket = aws_s3_bucket.audit_archive.id

  rule {
    id     = "expire-archives"
    status = "Enabled"
    filter {}

    expiration {
      days = var.audit_archive_retention_days
    }

    # バージョニング有効時は上記で削除マーカーが付くだけなので、実体も片付ける。
    noncurrent_version_expiration {
      noncurrent_days = var.audit_archive_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # 全バージョンが消えた後に残る削除マーカーを掃除する。
  # `expired_object_delete_marker` は同じ expiration 内で `days` と併用できないため、
  # ルールを分ける必要がある。
  rule {
    id     = "cleanup-expired-delete-markers"
    status = "Enabled"
    filter {}

    expiration {
      expired_object_delete_marker = true
    }
  }
}
