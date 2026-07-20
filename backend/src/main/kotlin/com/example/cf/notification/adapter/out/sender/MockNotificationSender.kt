package com.example.cf.notification.adapter.out.sender

import com.example.cf.notification.application.NotificationSenderPort
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
        log.info(
            "Mock notification sent: channel={} template={} recipientHash={}",
            channel, templateId, recipientAddress?.let { hashPrefix(it) } ?: "-",
        )
        return SendResult.Sent("mock_${UUID.randomUUID()}")
    }

    private fun hashPrefix(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
}
