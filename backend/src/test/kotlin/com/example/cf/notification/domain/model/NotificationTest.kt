package com.example.cf.notification.domain.model

import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.id.NotificationId
import com.example.cf.testsupport.Fixtures
import com.example.cf.testsupport.Fixtures.NOW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Notification集約の単体テスト（詳細設計 §4.6、§9.2 再試行方針）。
 */
class NotificationTest : FunSpec({

    fun notification(
        channel: NotificationChannel = NotificationChannel.EMAIL,
        recipientAddress: String? = "supporter@example.invalid",
    ) = Notification.create(
        id = NotificationId(Fixtures.newUlid()),
        businessKey = "SupportConfirmed:${Fixtures.newUlid()}",
        channel = channel,
        templateId = "SUPPORT_CONFIRMED",
        recipientUserId = Fixtures.userId(),
        recipientAddress = recipientAddress,
        variables = mapOf("eventType" to "SupportConfirmed"),
        now = NOW,
    )

    context("create") {
        test("生成直後はPENDING") {
            notification().status shouldBe NotificationStatus.PENDING
        }

        test("EMAILは宛先必須") {
            val e = shouldThrow<InvalidStateException> { notification(recipientAddress = null) }
            e.errorCode shouldBe "NOTIFICATION_RECIPIENT_REQUIRED"
        }

        test("IN_APPは宛先不要") {
            notification(channel = NotificationChannel.IN_APP, recipientAddress = null)
                .status shouldBe NotificationStatus.PENDING
        }
    }

    context("送信（BAT-005）") {
        test("startSending→markSentでSENTになる") {
            val n = notification()
            n.startSending(NOW.plusSeconds(60))
            n.status shouldBe NotificationStatus.SENDING
            n.markSent(NOW.plusSeconds(120))
            n.status shouldBe NotificationStatus.SENT
        }

        test("SENT後は再送信できない") {
            val n = notification()
            n.startSending(NOW.plusSeconds(60))
            n.markSent(NOW.plusSeconds(120))
            shouldThrow<InvalidStateException> { n.startSending(NOW.plusSeconds(180)) }
        }
    }

    context("失敗と再試行（§9.2: 最大5回）") {
        test("一時失敗はRETRY_WAITになり次回時刻が入る") {
            val n = notification()
            n.startSending(NOW.plusSeconds(60))
            n.fail(permanent = false, now = NOW.plusSeconds(120))
            n.status shouldBe NotificationStatus.RETRY_WAIT
            n.retryCount shouldBe 1
            n.nextRetryAt.shouldNotBeNull()
        }

        test("業務エラーは再試行せず即FAILED") {
            val n = notification()
            n.startSending(NOW.plusSeconds(60))
            n.fail(permanent = true, now = NOW.plusSeconds(120))
            n.status shouldBe NotificationStatus.FAILED
            n.nextRetryAt.shouldBeNull()
        }

        test("5回失敗でFAILEDになる") {
            val n = notification()
            repeat(Notification.MAX_RETRY_COUNT) { attempt ->
                n.startSending(NOW.plusSeconds(60L * (attempt + 1)))
                n.fail(permanent = false, now = NOW.plusSeconds(60L * (attempt + 1) + 30))
            }
            n.status shouldBe NotificationStatus.FAILED
            n.retryCount shouldBe Notification.MAX_RETRY_COUNT
        }
    }
})
