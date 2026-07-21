package com.example.cf.funding.domain.model

import com.example.cf.shared.kernel.IdempotencyKey
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.SupportItemId
import com.example.cf.shared.kernel.money.Money
import com.example.cf.testsupport.Fixtures
import com.example.cf.testsupport.Fixtures.NOW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Support集約の単体テスト（詳細設計 §4.3、§5.3）。
 */
class SupportTest :
    FunSpec({

        fun item(amount: Long = 3_000, quantity: Int = 1) = SupportItem(
            id = SupportItemId(Fixtures.newUlid()),
            rewardPlanId = null,
            quantity = quantity,
            unitAmount = Money.of(amount),
            amount = Money.of(amount * quantity),
        )

        fun support(items: List<SupportItem> = listOf(item())) = Support.request(
            id = SupportId(Fixtures.newUlid()),
            projectId = Fixtures.projectId(),
            supporterUserId = Fixtures.userId(),
            items = items,
            idempotencyKey = IdempotencyKey("key-0001"),
            contactEmail = "supporter@example.invalid",
            now = NOW,
        )

        context("request（FD-U-001）") {
            test("明細合計が支援額になりPENDINGで生成される") {
                val s = support(listOf(item(3_000, 2), item(1_000)))
                s.amount shouldBe Money.of(7_000)
                s.status shouldBe SupportStatus.PENDING
                s.version shouldBe Version(0)
            }

            test("明細なしは生成できない") {
                shouldThrow<ValidationException> { support(emptyList()) }
            }

            test("上限100,000,000円超は生成できない") {
                val e = shouldThrow<ValidationException> {
                    support(listOf(item(Support.MAX_SUPPORT_AMOUNT + 1)))
                }
                e.errorCode shouldBe "SUPPORT_AMOUNT_INVALID"
            }

            test("上限ちょうどは生成できる") {
                support(listOf(item(Support.MAX_SUPPORT_AMOUNT))).amount shouldBe
                    Money.of(Support.MAX_SUPPORT_AMOUNT)
            }

            test("メール形式不正は生成できない") {
                shouldThrow<ValidationException> {
                    Support.request(
                        id = SupportId(Fixtures.newUlid()),
                        projectId = Fixtures.projectId(),
                        supporterUserId = Fixtures.userId(),
                        items = listOf(item()),
                        idempotencyKey = IdempotencyKey("key-0001"),
                        contactEmail = "not-an-email",
                        now = NOW,
                    )
                }
            }

            test("数量0の明細は作成できない") {
                shouldThrow<IllegalArgumentException> { item(quantity = 0) }
            }
        }

        context("状態遷移（FD-U-002）") {
            test("決済成功でPAIDになる") {
                val s = support()
                val event = s.confirm(NOW.plusSeconds(60))
                s.status shouldBe SupportStatus.PAID
                s.version shouldBe Version(1)
                event.eventType shouldBe "SupportConfirmed"
            }

            test("決済失敗でPAYMENT_FAILEDになる") {
                val s = support()
                s.failPayment(NOW.plusSeconds(60)).eventType shouldBe "SupportPaymentFailed"
                s.status shouldBe SupportStatus.PAYMENT_FAILED
            }

            test("PAID後の再確定はできない") {
                val s = support()
                s.confirm(NOW.plusSeconds(60))
                val e = shouldThrow<InvalidStateException> { s.confirm(NOW.plusSeconds(120)) }
                e.errorCode shouldBe "SUPPORT_INVALID_STATE"
            }
        }

        context("取消（FD-U-003）") {
            test("決済確定前の取消は即CANCELLED") {
                val s = support()
                s.cancel(NOW.plusSeconds(60))
                s.status shouldBe SupportStatus.CANCELLED
            }

            test("確定後の取消はCANCEL_REQUESTED（返金フローへ）") {
                val s = support()
                s.confirm(NOW.plusSeconds(60))
                s.cancel(NOW.plusSeconds(120))
                s.status shouldBe SupportStatus.CANCEL_REQUESTED
            }

            test("CANCELLED後の再取消はできない") {
                val s = support()
                s.cancel(NOW.plusSeconds(60))
                shouldThrow<InvalidStateException> { s.cancel(NOW.plusSeconds(120)) }
            }
        }

        context("返金（FD-U-004）") {
            test("PAIDから返金要求・処理中・完了へ進む（基本設計 §3.5）") {
                val s = support()
                s.confirm(NOW.plusSeconds(60))
                s.requireRefund(NOW.plusSeconds(120)).eventType shouldBe "RefundRequired"
                s.status shouldBe SupportStatus.REFUND_REQUESTED
                s.startRefund(NOW.plusSeconds(180))
                s.status shouldBe SupportStatus.REFUNDING
                s.markRefunded(NOW.plusSeconds(240))
                s.status shouldBe SupportStatus.REFUNDED
            }

            test("返金失敗後は再実行できる") {
                val s = support()
                s.confirm(NOW.plusSeconds(60))
                s.requireRefund(NOW.plusSeconds(120))
                s.startRefund(NOW.plusSeconds(180))
                s.failRefund(NOW.plusSeconds(240))
                s.status shouldBe SupportStatus.REFUND_FAILED
                s.startRefund(NOW.plusSeconds(300))
                s.status shouldBe SupportStatus.REFUNDING
            }

            test("PENDINGからは返金要求できない") {
                shouldThrow<InvalidStateException> { support().requireRefund(NOW.plusSeconds(60)) }
            }

            test("返金要求直後に完了はできない（REFUNDINGを経由する）") {
                val s = support()
                s.confirm(NOW.plusSeconds(60))
                s.requireRefund(NOW.plusSeconds(120))
                shouldThrow<InvalidStateException> { s.markRefunded(NOW.plusSeconds(180)) }
            }
        }
    })
