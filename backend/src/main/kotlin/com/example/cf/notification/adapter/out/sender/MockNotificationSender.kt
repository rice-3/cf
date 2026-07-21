package com.example.cf.notification.adapter.out.sender

import com.example.cf.notification.application.NotificationSenderPort
import com.example.cf.notification.application.NotificationTemplateCatalog
import com.example.cf.notification.application.SendResult
import com.example.cf.notification.domain.model.NotificationChannel
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.UUID

/**
 * 通知送信のMock（local/test）。実際のメールは送信しない。
 *
 * 本文は [NotificationTemplateCatalog] でレンダリングし、内容確認できるようログへ出す。
 * 宛先は平文でログ出力せず、SHA-256ハッシュの先頭のみを出す（詳細設計 §10.3）。
 */
@Component
@Profile("local", "test")
class MockNotificationSender : NotificationSenderPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(
        channel: NotificationChannel,
        templateId: String,
        recipientAddress: String?,
        variables: Map<String, Any?>,
    ): SendResult {
        if (channel == NotificationChannel.EMAIL && recipientAddress.isNullOrBlank()) {
            return SendResult.Failed("RECIPIENT_MISSING", permanent = true)
        }
        val rendered = NotificationTemplateCatalog.render(templateId, variables)
        if (rendered == null) {
            // テンプレート未定義は恒久エラー（SES登録漏れと同じ扱い、§8.2）
            log.warn("Unknown notification template: {}", templateId)
            return SendResult.Failed("TEMPLATE_NOT_FOUND", permanent = true)
        }
        log.info(
            "Mock notification sent: channel={} template={} recipientHash={} subject=\"{}\" body=\"{}\"",
            channel, templateId, recipientAddress?.let { hashPrefix(it) } ?: "-",
            rendered.subject, rendered.textBody.replace("\n", " / "),
        )
        return SendResult.Sent("mock_${UUID.randomUUID()}")
    }

    private fun hashPrefix(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
}
