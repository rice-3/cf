package com.example.cf.payment.domain.model

import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.RefundId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.money.Money
import com.example.cf.testsupport.Fixtures
import com.example.cf.testsupport.Fixtures.NOW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Refund集約の単体テスト（詳細設計 §4.5、§9.2 再試行方針）。
 */
class RefundTest : FunSpec({

    fun refund(
        reasonCode: RefundReasonCode = RefundReasonCode.PROJECT_FAILED,
        comment: String? = null,
    ) = Refund.request(
        id = RefundId(Fixtures.newUlid()),
        paymentId = PaymentId(Fixtures.newUlid()),
        supportId = SupportId(Fixtures.newUlid()),
        amount = Money.of(5_000),
        reasonCode = reasonCode,
        comment = comment,
        now = NOW,
    )

    context("request") {
        test("生成直後はREQUESTED") {
            val r = refund()
            r.status shouldBe RefundStatus.REQUESTED
            r.retryCount shouldBe 0
            r.nextRetryAt.shouldBeNull()
        }

        test("運用理由はコメント必須") {
            val e = shouldThrow<InvalidStateException> {
                refund(reasonCode = RefundReasonCode.OPERATIONAL, comment = null)
            }
            e.errorCode shouldBe "REFUND_COMMENT_REQUIRED"
        }

        test("運用理由でもコメントがあれば生成できる") {
            refund(reasonCode = RefundReasonCode.OPERATIONAL, comment = "重複支援のため")
                .status shouldBe RefundStatus.REQUESTED
        }
    }

    context("成功経路") {
        test("start→succeedでSUCCEEDEDになり外部IDを保持する") {
            val r = refund()
            r.start(NOW.plusSeconds(60))
            r.status shouldBe RefundStatus.PROCESSING
            val event = r.succeed("sbxrf_0001", NOW.plusSeconds(120))
            r.status shouldBe RefundStatus.SUCCEEDED
            r.providerRefundId shouldBe "sbxrf_0001"
            event.eventType shouldBe "RefundCompleted"
        }

        test("REQUESTED以外からはstartできない") {
            val r = refund()
            r.start(NOW.plusSeconds(60))
            shouldThrow<InvalidStateException> { r.start(NOW.plusSeconds(120)) }
        }
    }

    context("失敗と再試行（§9.2: 最大8回）") {
        test("失敗するとRETRY_WAITになり次回時刻が入る") {
            val r = refund()
            r.start(NOW.plusSeconds(60))
            r.fail("PROVIDER_UNAVAILABLE", NOW.plusSeconds(120)).shouldBeNull()
            r.status shouldBe RefundStatus.RETRY_WAIT
            r.retryCount shouldBe 1
            r.nextRetryAt.shouldNotBeNull()
        }

        test("RETRY_WAITから再度startできる") {
            val r = refund()
            r.start(NOW.plusSeconds(60))
            r.fail(null, NOW.plusSeconds(120))
            r.start(NOW.plusSeconds(180))
            r.status shouldBe RefundStatus.PROCESSING
        }

        test("8回失敗でFAILEDになりRefundFailedを返す") {
            val r = refund()
            var event: com.example.cf.payment.domain.event.RefundFailed? = null
            repeat(Refund.MAX_RETRY_COUNT) { attempt ->
                r.start(NOW.plusSeconds(60L * (attempt + 1)))
                event = r.fail("PROVIDER_UNAVAILABLE", NOW.plusSeconds(60L * (attempt + 1) + 30))
            }
            r.status shouldBe RefundStatus.FAILED
            r.retryCount shouldBe Refund.MAX_RETRY_COUNT
            event.shouldNotBeNull()
            event.eventType shouldBe "RefundFailed"
        }

        test("PROCESSING以外からはfailできない") {
            shouldThrow<InvalidStateException> { refund().fail(null, NOW.plusSeconds(60)) }
        }
    }

    context("OPERATORによる再実行（API-RF-002）") {
        test("RETRY_WAITからREQUESTEDへ戻せる") {
            val r = refund()
            r.start(NOW.plusSeconds(60))
            r.fail(null, NOW.plusSeconds(120))
            r.retry(NOW.plusSeconds(180))
            r.status shouldBe RefundStatus.REQUESTED
            r.nextRetryAt.shouldBeNull()
        }

        test("恒久失敗後もOPERATORは再実行できる") {
            val r = refund()
            repeat(Refund.MAX_RETRY_COUNT) { attempt ->
                r.start(NOW.plusSeconds(60L * (attempt + 1)))
                r.fail(null, NOW.plusSeconds(60L * (attempt + 1) + 30))
            }
            r.status shouldBe RefundStatus.FAILED
            r.retry(NOW.plusSeconds(9_999))
            r.status shouldBe RefundStatus.REQUESTED
        }

        test("SUCCEEDED後は再実行できない") {
            val r = refund()
            r.start(NOW.plusSeconds(60))
            r.succeed(null, NOW.plusSeconds(120))
            shouldThrow<InvalidStateException> { r.retry(NOW.plusSeconds(180)) }
        }
    }
})
