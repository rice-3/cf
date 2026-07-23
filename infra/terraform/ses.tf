# SES送信ドメイン検証（基本設計 §6.10 / 詳細設計 §8.16 通知）。
# var.ses_domain 指定時のみ作成する。DKIMのCNAMEをDNSへ登録して検証を完了する。
# メールテンプレートの実登録（NotificationTemplateCatalogを正）は別タスク（残 §3.1）。

resource "aws_sesv2_email_identity" "domain" {
  count          = var.ses_domain != "" ? 1 : 0
  email_identity = var.ses_domain

  dkim_signing_attributes {
    next_signing_key_length = "RSA_2048_BIT"
  }

  tags = { Name = "${local.name_prefix}-ses-domain" }
}

# 送信イベント（bounce/complaint/delivery）を集約する構成セット。
resource "aws_sesv2_configuration_set" "main" {
  configuration_set_name = "${local.name_prefix}-ses"

  delivery_options {
    tls_policy = "REQUIRE"
  }

  reputation_options {
    reputation_metrics_enabled = true
  }

  sending_options {
    sending_enabled = true
  }
}
