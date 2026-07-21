package com.example.cf.payment.adapter.out.gateway

import com.example.cf.payment.application.PaymentProperties
import com.example.cf.payment.application.ProviderPaymentStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 決済Sandbox Adapterの署名検証テスト（詳細設計 §10.4、§6.8）。
 */
class SandboxWebhookVerificationTest :
    FunSpec({

        val now = Instant.parse("2026-07-20T00:00:00Z")
        val secret = "test-secret"
        val properties = PaymentProperties(webhookSecret = secret, webhookToleranceSeconds = 300)
        val adapter = SandboxPaymentGatewayAdapter(
            properties,
            JsonMapper.builder().build(),
            Clock.fixed(now, ZoneOffset.UTC),
        )

        val body = """{"eventId":"evt-1","eventType":"payment.succeeded","providerPaymentId":"sbx_1"}"""

        fun sign(timestamp: String, rawBody: String = body) = SandboxPaymentGatewayAdapter.signForTesting(secret, timestamp, rawBody)

        test("正しい署名と時刻なら検証に成功する") {
            val ts = now.toString()
            val verified = adapter.verifyWebhook(sign(ts), ts, body)
            verified.shouldNotBeNull()
            verified.eventId shouldBe "evt-1"
            verified.eventType shouldBe "payment.succeeded"
            verified.providerPaymentId shouldBe "sbx_1"
        }

        test("署名が異なる場合はnull") {
            adapter.verifyWebhook("deadbeef", now.toString(), body).shouldBeNull()
        }

        test("署名ヘッダー欠落はnull") {
            adapter.verifyWebhook(null, now.toString(), body).shouldBeNull()
        }

        test("bodyが改ざんされた場合は署名不一致でnull") {
            val ts = now.toString()
            val signature = sign(ts)
            val tampered = body.replace("payment.succeeded", "payment.failed")
            adapter.verifyWebhook(signature, ts, tampered).shouldBeNull()
        }

        test("許容差5分を超える時刻はnull") {
            val stale = now.minusSeconds(301).toString()
            adapter.verifyWebhook(sign(stale), stale, body).shouldBeNull()
        }

        test("許容差ちょうどは検証に成功する") {
            val edge = now.minusSeconds(300).toString()
            adapter.verifyWebhook(sign(edge), edge, body).shouldNotBeNull()
        }

        test("必須項目を欠くpayloadはnull") {
            val incomplete = """{"eventId":"evt-2"}"""
            val ts = now.toString()
            adapter.verifyWebhook(sign(ts, incomplete), ts, incomplete).shouldBeNull()
        }

        test("createPaymentは同一paymentIdへ同じ外部IDを返す") {
            val first = adapter.createPayment("01K00000000000000000000009", 5000)
            val second = adapter.createPayment("01K00000000000000000000009", 5000)
            first.status shouldBe ProviderPaymentStatus.ACCEPTED
            first.providerPaymentId shouldBe second.providerPaymentId
        }
    })
