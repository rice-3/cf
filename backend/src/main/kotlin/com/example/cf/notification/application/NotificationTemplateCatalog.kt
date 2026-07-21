package com.example.cf.notification.application

/**
 * 通知テンプレートの本文定義（詳細設計 §9.3 SESテンプレート、基本設計 §4.6）。
 *
 * テンプレートID → 件名・本文の**単一の正**をコードで管理する。
 * - local/test: [com.example.cf.notification.adapter.out.sender.MockNotificationSender] が
 *   本カタログでレンダリングしてログ出力する（Mailpit相当の内容確認用）。
 * - dev以上: Amazon SESへ同一内容のテンプレートを登録する（templateNameはテンプレートID）。
 *   SES登録は本カタログを正とし、Terraform（`aws_sesv2_email_template`）またはCLIで反映する。
 *   詳細は docs/operations 参照（Terraformは工程10の残タスク）。
 *
 * 変数は `{{key}}`（Handlebars形式、SES準拠）で埋め込む。個人情報を含めない（§10.3）。
 */
data class NotificationTemplateContent(
    val subject: String,
    /** プレーンテキスト本文。`{{key}}` を variables で置換する。 */
    val textBody: String,
)

object NotificationTemplateCatalog {

    private val TEMPLATES: Map<String, NotificationTemplateContent> = mapOf(
        // ---- 支援者向け ----
        "SUPPORT_CONFIRMED" to NotificationTemplateContent(
            subject = "【CF-Training】ご支援を受け付けました",
            textBody = "ご支援ありがとうございます。支援ID {{resourceId}} を受け付けました。\n" +
                "詳細はマイページの支援履歴からご確認ください。",
        ),
        "SUPPORT_PAYMENT_FAILED" to NotificationTemplateContent(
            subject = "【CF-Training】決済が完了しませんでした",
            textBody = "支援ID {{resourceId}} の決済を完了できませんでした。\n" +
                "お手数ですが、支援履歴から状態をご確認のうえ再度お手続きください。",
        ),
        "REFUND_COMPLETED" to NotificationTemplateContent(
            subject = "【CF-Training】返金が完了しました",
            textBody = "支援ID {{resourceId}} の返金が完了しました。\n" +
                "ご不明点はお問い合わせください。",
        ),
        // ---- 起案者向け（ADR-0002） ----
        "PROJECT_APPROVED" to NotificationTemplateContent(
            subject = "【CF-Training】プロジェクトが承認されました",
            textBody = "プロジェクト {{resourceId}} の審査が承認されました。\n" +
                "募集開始日時になると自動的に公開されます。",
        ),
        "PROJECT_RETURNED" to NotificationTemplateContent(
            subject = "【CF-Training】プロジェクトが差し戻されました",
            textBody = "プロジェクト {{resourceId}} が審査で差し戻されました。\n" +
                "審査コメントをご確認のうえ、修正して再申請してください。",
        ),
        "PROJECT_REJECTED" to NotificationTemplateContent(
            subject = "【CF-Training】プロジェクトが却下されました",
            textBody = "プロジェクト {{resourceId}} は審査の結果、却下されました。\n" +
                "詳細は審査結果をご確認ください。",
        ),
        "PROJECT_PUBLISHED" to NotificationTemplateContent(
            subject = "【CF-Training】プロジェクトが公開されました",
            textBody = "プロジェクト {{resourceId}} が公開され、支援の受付を開始しました。",
        ),
        "PROJECT_SUCCEEDED" to NotificationTemplateContent(
            subject = "【CF-Training】募集が成立しました",
            textBody = "プロジェクト {{resourceId}} の募集が成立しました。\n" +
                "精算手続きへ進みます。",
        ),
        "PROJECT_FAILED" to NotificationTemplateContent(
            subject = "【CF-Training】募集が不成立となりました",
            textBody = "プロジェクト {{resourceId}} の募集は目標条件を満たさず不成立となりました。\n" +
                "支援者への返金手続きが行われます。",
        ),
    )

    /** 登録済みテンプレートID一覧（SES登録の対象）。 */
    val templateIds: Set<String> get() = TEMPLATES.keys

    fun find(templateId: String): NotificationTemplateContent? = TEMPLATES[templateId]

    /**
     * 件名・本文の `{{key}}` を variables で置換してレンダリングする。
     * 未知の変数は空文字にせず、プレースホルダをそのまま残す（テンプレート不整合を検知しやすくする）。
     */
    fun render(templateId: String, variables: Map<String, Any?>): NotificationTemplateContent? {
        val template = TEMPLATES[templateId] ?: return null
        return NotificationTemplateContent(
            subject = substitute(template.subject, variables),
            textBody = substitute(template.textBody, variables),
        )
    }

    private val PLACEHOLDER = Regex("\\{\\{\\s*(\\w+)\\s*}}")

    private fun substitute(text: String, variables: Map<String, Any?>): String =
        PLACEHOLDER.replace(text) { match ->
            val key = match.groupValues[1]
            variables[key]?.toString() ?: match.value
        }
}
