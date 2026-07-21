package com.example.cf.notification.adapter.out.sender

import com.example.cf.notification.application.NotificationSenderPort
import com.example.cf.notification.application.SendResult
import com.example.cf.notification.domain.model.NotificationChannel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.Template
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

/** SES設定（詳細設計 §10.3、§13.1 環境変数）。 */
@ConfigurationProperties(prefix = "cf.notification.ses")
data class SesProperties(
    /** 検証済みドメインの送信元アドレス。環境別に設定する。 */
    val fromAddress: String = "no-reply@example.invalid",
    val configurationSetName: String? = null,
)

/**
 * Amazon SES送信Adapter（詳細設計 §10.3）。dev以上の環境で使用する。
 *
 * 本文はSESテンプレート（template_idで指定）＋変数で構成し、本文をアプリ側で組み立てない。
 * 宛先は平文でログ出力しない。
 */
@Component
@Profile("!local & !test")
class SesNotificationSender(
    private val properties: SesProperties,
    private val objectMapper: ObjectMapper,
) : NotificationSenderPort,
    DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client: SesV2Client = SesV2Client.create()

    override fun send(
        channel: NotificationChannel,
        templateId: String,
        recipientAddress: String?,
        variables: Map<String, Any?>,
    ): SendResult {
        if (channel != NotificationChannel.EMAIL) {
            // IN_APPはDB上の通知レコードのみで完結する
            return SendResult.Sent(null)
        }
        if (recipientAddress.isNullOrBlank()) {
            return SendResult.Failed("RECIPIENT_MISSING", permanent = true)
        }

        val request = SendEmailRequest.builder()
            .fromEmailAddress(properties.fromAddress)
            .destination(Destination.builder().toAddresses(recipientAddress).build())
            .content(
                EmailContent.builder()
                    .template(
                        Template.builder()
                            .templateName(templateId)
                            .templateData(objectMapper.writeValueAsString(variables))
                            .build(),
                    )
                    .build(),
            )
            .apply { properties.configurationSetName?.let { configurationSetName(it) } }
            .build()

        return try {
            val response = client.sendEmail(request)
            SendResult.Sent(response.messageId())
        } catch (e: MessageRejectedException) {
            // 宛先・内容起因の拒否は再試行しても解決しない（基本設計 §8.2）
            SendResult.Failed("MESSAGE_REJECTED", permanent = true)
        } catch (e: MailFromDomainNotVerifiedException) {
            SendResult.Failed("DOMAIN_NOT_VERIFIED", permanent = true)
        } catch (e: AccountSuspendedException) {
            SendResult.Failed("ACCOUNT_SUSPENDED", permanent = true)
        } catch (e: SdkException) {
            log.warn("SES send failed: template={} recipientHash={}", templateId, hashPrefix(recipientAddress), e)
            SendResult.Failed("SES_UNAVAILABLE", permanent = false)
        }
    }

    override fun destroy() {
        client.close()
    }

    private fun hashPrefix(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(12)
}
