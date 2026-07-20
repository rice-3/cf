package com.example.cf.payment.domain.model

import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.money.Money
import com.example.cf.testsupport.Fixtures
import com.example.cf.testsupport.Fixtures.NOW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Payment集約の単体テスト（詳細設計 §4.4 状態遷移表）。
 */
class PaymentTest : FunSpec({

    fun payment() = Payment.create(
        id = PaymentId(Fixtures.newUlid()),
        supportId = SupportId(Fixtures.newUlid()),
        provider = "SANDBOX",
        amount = Money.of(5_000),
        now = NOW,
    )

    test("生成直後はCREATED") {
        payment().status shouldBe PaymentStatus.CREATED
    }

    test("CREATED→PROCESSING→SUCCEEDEDへ遷移し外部IDと確定日時が記録される") {
        val p = payment()
        p.startProcessing("pay_ext_0001", NOW.plusSeconds(10))
        p.status shouldBe PaymentStatus.PROCESSING
        p.succeed("pay_ext_0001", NOW.plusSeconds(20)).eventType shouldBe "PaymentSucceeded"
        p.status shouldBe PaymentStatus.SUCCEEDED
        p.providerPaymentId shouldBe "pay_ext_0001"
        p.processedAt.shouldNotBeNull()
    }

    test("PROCESSINGからFAILEDへ遷移し失敗コードを保持する") {
        val p = payment()
        p.startProcessing(null, NOW.plusSeconds(10))
        p.fail("CARD_DECLINED", NOW.plusSeconds(20))
        p.status shouldBe PaymentStatus.FAILED
        p.failureCode shouldBe "CARD_DECLINED"
    }

    test("CREATEDから直接SUCCEEDEDへは遷移できない") {
        val e = shouldThrow<InvalidStateException> { payment().succeed(null, NOW.plusSeconds(10)) }
        e.errorCode shouldBe "PAYMENT_INVALID_STATE"
    }

    test("FAILEDは終端で以後遷移できない") {
        val p = payment()
        p.startProcessing(null, NOW.plusSeconds(10))
        p.fail(null, NOW.plusSeconds(20))
        shouldThrow<InvalidStateException> { p.succeed(null, NOW.plusSeconds(30)) }
    }

    test("UNKNOWNから照合でSUCCEEDEDへ確定できる") {
        val p = payment()
        p.startProcessing(null, NOW.plusSeconds(10))
        p.markUnknown(NOW.plusSeconds(20)).eventType shouldBe "PaymentReconciliationRequired"
        p.status shouldBe PaymentStatus.UNKNOWN
        p.succeed("pay_ext_0002", NOW.plusSeconds(30))
        p.status shouldBe PaymentStatus.SUCCEEDED
    }

    test("SUCCEEDEDから返金要求・返金完了へ進む") {
        val p = payment()
        p.startProcessing(null, NOW.plusSeconds(10))
        p.succeed(null, NOW.plusSeconds(20))
        p.requireRefund(NOW.plusSeconds(30))
        p.status shouldBe PaymentStatus.REFUND_PENDING
        p.markRefunded(NOW.plusSeconds(40))
        p.status shouldBe PaymentStatus.REFUNDED
    }
})
