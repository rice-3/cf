package com.example.cf.funding.application

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.funding.domain.model.Support
import com.example.cf.funding.domain.model.SupportItem
import com.example.cf.funding.domain.model.SupportStatus
import com.example.cf.funding.domain.repository.SupportRepository
import com.example.cf.funding.domain.service.FundingEligibilityPolicy
import com.example.cf.funding.domain.service.SupportabilityInput
import com.example.cf.identity.application.UserReferenceQuery
import com.example.cf.payment.application.CreatePaymentForSupportUseCase
import com.example.cf.project.application.query.ProjectReferenceQuery
import com.example.cf.shared.idempotency.IdempotencyOutcome
import com.example.cf.shared.idempotency.IdempotencyPort
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.CurrentUser
import com.example.cf.shared.kernel.IdempotencyKey
import com.example.cf.shared.kernel.RoleCode
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.RewardPlanId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.SupportItemId
import com.example.cf.shared.kernel.id.UlidGenerator
import com.example.cf.shared.kernel.money.Money
import com.example.cf.shared.outbox.OutboxAppendPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

private const val RESOURCE_TYPE = "Support"

/** 冪等スコープ（idempotency_record.scope、§8.21）。 */
private const val IDEMPOTENCY_SCOPE = "SUPPORT_CREATE"

// ---- コマンド・結果 ---------------------------------------------------------

data class RequestSupportCommand(
    val projectId: ProjectId,
    val rewardPlanId: RewardPlanId?,
    val quantity: Int,
    val additionalAmount: Long,
    val contactEmail: String,
    val termsAccepted: Boolean,
    val idempotencyKey: IdempotencyKey,
)

data class RequestSupportResult(
    val supportId: SupportId,
    val paymentStatus: String,
    val statusUrl: String,
    /** 冪等再生（初回結果の再返却）の場合true。 */
    val replayed: Boolean = false,
)

data class CancelSupportCommand(val supportId: SupportId)

data class CancelSupportResult(val supportId: SupportId, val status: SupportStatus)

// ---- UC-FD-001 支援申込（API-FD-001、詳細設計 §5.3） ------------------------

interface RequestSupportUseCase {
    fun execute(command: RequestSupportCommand, currentUser: CurrentUser, audit: AuditContext): RequestSupportResult
}

@Service
class RequestSupportService(
    private val supportRepository: SupportRepository,
    private val projectReferenceQuery: ProjectReferenceQuery,
    private val userReferenceQuery: UserReferenceQuery,
    private val eligibilityPolicy: FundingEligibilityPolicy,
    private val createPayment: CreatePaymentForSupportUseCase,
    private val idempotency: IdempotencyPort,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
    private val idGenerator: UlidGenerator,
) : RequestSupportUseCase {

    /**
     * §5.3の順で処理する。外部決済APIはこのトランザクション内で呼ばない（§5.3.1）。
     * 永続化は reward_plan / support / payment / idempotency_record / outbox_event の5レコード。
     */
    @Transactional
    override fun execute(
        command: RequestSupportCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): RequestSupportResult {
        currentUser.requireRole(RoleCode.SUPPORTER)

        if (!command.termsAccepted) {
            throw ValidationException(message = "termsAccepted must be true")
        }

        // 1. 冪等性（§5.3: userId＋keyで一意。完了済みは保存済み応答、処理中は409）
        val outcome = idempotency.begin(
            scope = IDEMPOTENCY_SCOPE,
            actorId = currentUser.userId.value,
            key = command.idempotencyKey,
            requestHash = requestHash(command),
        )
        if (outcome is IdempotencyOutcome.Replay) {
            val supportId = outcome.responseBody["supportId"] as String
            return RequestSupportResult(
                supportId = SupportId(supportId),
                paymentStatus = outcome.responseBody["paymentStatus"] as? String ?: "PENDING",
                statusUrl = outcome.responseBody["statusUrl"] as? String ?: statusUrl(supportId),
                replayed = true,
            )
        }

        val now = clock.instant()

        // 2. 支援可否（公開・期間・会員状態）
        val project = projectReferenceQuery.findSupportability(command.projectId)
            ?: throw ResourceNotFoundException(
                "PROJECT_NOT_FOUND",
                "Project ${command.projectId.value} is not found",
            )
        eligibilityPolicy.validateOrThrow(
            SupportabilityInput(
                projectStatus = project.status.name,
                fundingStart = project.startAt,
                fundingEnd = project.endAt,
                supporterActive = userReferenceQuery.isActive(currentUser.userId.value),
            ),
            now,
        )

        // 3〜4. 明細確定と数量予約（条件付きUPDATE、§4.3.1）
        val items = buildItems(command, now)

        // 5〜6. Support / Payment 生成
        val support = Support.request(
            id = SupportId.newId(idGenerator),
            projectId = command.projectId,
            supporterUserId = currentUser.userId,
            items = items,
            idempotencyKey = command.idempotencyKey,
            contactEmail = command.contactEmail,
            now = now,
        )
        supportRepository.save(support)

        val paymentId = createPayment.execute(support.id, support.amount, audit.correlationId)
        support.attachPayment(paymentId, now)
        supportRepository.save(support)

        // 7. Outbox / Audit / 冪等応答
        outbox.append(Support.requestedEvent(support, paymentId, now), audit.correlationId)
        auditPort.record(audit, "SUPPORT_REQUEST", RESOURCE_TYPE, support.id.value, "SUCCESS")

        val result = RequestSupportResult(
            supportId = support.id,
            paymentStatus = "PENDING",
            statusUrl = statusUrl(support.id.value),
        )
        idempotency.complete(
            scope = IDEMPOTENCY_SCOPE,
            actorId = currentUser.userId.value,
            key = command.idempotencyKey,
            responseStatus = 202,
            responseBody = mapOf(
                "supportId" to result.supportId.value,
                "paymentStatus" to result.paymentStatus,
                "statusUrl" to result.statusUrl,
            ),
        )
        return result
    }

    private fun buildItems(command: RequestSupportCommand, now: Instant): List<SupportItem> {
        val items = mutableListOf<SupportItem>()

        command.rewardPlanId?.let { rewardPlanId ->
            val reward = projectReferenceQuery.findRewardPlan(rewardPlanId.value)
                ?: throw ResourceNotFoundException(
                    "REWARD_PLAN_NOT_FOUND",
                    "Reward plan ${rewardPlanId.value} is not found",
                )
            if (reward.projectId != command.projectId.value) {
                throw ValidationException(message = "rewardPlanId does not belong to the project")
            }
            // 数量上限を超える場合409 REWARD_SOLD_OUT（§5.3）。競合は1回だけ再試行する（§5.5）。
            val reserved = projectReferenceQuery.reserveRewardQuantity(
                reward.rewardPlanId,
                command.quantity,
                reward.version,
            ) ||
                retryReserve(reward.rewardPlanId, command.quantity)
            if (!reserved) {
                throw InvalidStateException(
                    "REWARD_SOLD_OUT",
                    "Reward plan ${reward.rewardPlanId} does not have enough quantity",
                )
            }
            val unitAmount = Money.of(reward.unitAmount)
            items += SupportItem(
                id = SupportItemId.newId(idGenerator),
                rewardPlanId = rewardPlanId,
                quantity = command.quantity,
                unitAmount = unitAmount,
                amount = unitAmount * command.quantity,
            )
        }

        if (command.additionalAmount > 0) {
            val additional = Money.of(command.additionalAmount)
            items += SupportItem(
                id = SupportItemId.newId(idGenerator),
                rewardPlanId = null,
                quantity = 1,
                unitAmount = additional,
                amount = additional,
            )
        }

        if (items.isEmpty()) {
            throw ValidationException(message = "rewardPlanId or additionalAmount is required")
        }
        return items
    }

    /** version競合時の1回再試行（§5.5 Reward数量）。最新versionを読み直して再実行する。 */
    private fun retryReserve(rewardPlanId: String, quantity: Int): Boolean {
        val latest = projectReferenceQuery.findRewardPlan(rewardPlanId) ?: return false
        return projectReferenceQuery.reserveRewardQuantity(rewardPlanId, quantity, latest.version)
    }

    private fun statusUrl(supportId: String) = "/api/v1/me/supports/$supportId"

    /** 同一キーで内容が異なる要求を検出するためのハッシュ（§8.21 request_hash）。 */
    private fun requestHash(command: RequestSupportCommand): String {
        val canonical = listOf(
            command.projectId.value,
            command.rewardPlanId?.value ?: "",
            command.quantity.toString(),
            command.additionalAmount.toString(),
            command.contactEmail,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

// ---- API-FD-004 支援取消 ----------------------------------------------------

interface CancelSupportUseCase {
    fun execute(command: CancelSupportCommand, currentUser: CurrentUser, audit: AuditContext): CancelSupportResult
}

@Service
class CancelSupportService(
    private val supportRepository: SupportRepository,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : CancelSupportUseCase {

    @Transactional
    override fun execute(
        command: CancelSupportCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): CancelSupportResult {
        currentUser.requireRole(RoleCode.SUPPORTER)

        val support = supportRepository.findByIdForUpdate(command.supportId)
        // 他者の支援は存在秘匿のため404
        if (support == null || support.supporterUserId != currentUser.userId) {
            throw ResourceNotFoundException(
                "SUPPORT_NOT_FOUND",
                "Support ${command.supportId.value} is not found",
            )
        }

        val event = support.cancel(clock.instant())
        supportRepository.save(support)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "SUPPORT_CANCEL", RESOURCE_TYPE, support.id.value, "SUCCESS")
        return CancelSupportResult(support.id, support.status)
    }
}
