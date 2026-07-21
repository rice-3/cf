package com.example.cf.project.domain.event

import com.example.cf.project.domain.model.FundingType
import com.example.cf.shared.kernel.event.DomainEvent
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.ReviewId
import com.example.cf.shared.kernel.id.UserId
import java.time.Instant

/** Project集約のドメインイベント（詳細設計 §4.1、基本設計 §4.6）。 */
sealed interface ProjectDomainEvent : DomainEvent {
    val projectId: ProjectId

    override val aggregateType: String get() = "Project"
    override val aggregateId: String get() = projectId.value
}

data class ProjectCreated(
    override val projectId: ProjectId,
    val ownerUserId: UserId,
    val occurredAt: Instant,
) : ProjectDomainEvent {
    override val eventType: String = "ProjectCreated"
}

data class ProjectSubmittedForReview(
    override val projectId: ProjectId,
    val reviewId: ReviewId,
    val ownerUserId: UserId,
    val occurredAt: Instant,
) : ProjectDomainEvent {
    override val eventType: String = "ProjectSubmittedForReview"
}

data class ProjectReviewStarted(
    override val projectId: ProjectId,
    val reviewId: ReviewId,
    val reviewerUserId: UserId,
    val occurredAt: Instant,
) : ProjectDomainEvent {
    override val eventType: String = "ProjectReviewStarted"
}

data class ProjectApproved(
    override val projectId: ProjectId,
    val ownerUserId: UserId,
    val reviewId: ReviewId,
    val reviewerUserId: UserId,
    val occurredAt: Instant,
) : ProjectDomainEvent {
    override val eventType: String = "ProjectApproved"
}

data class ProjectReturned(
    override val projectId: ProjectId,
    val ownerUserId: UserId,
    val reviewId: ReviewId,
    val reviewerUserId: UserId,
    val comment: String,
    val occurredAt: Instant,
) : ProjectDomainEvent {
    override val eventType: String = "ProjectReturned"
}

data class ProjectRejected(
    override val projectId: ProjectId,
    val ownerUserId: UserId,
    val reviewId: ReviewId,
    val reviewerUserId: UserId,
    val reasonCode: String,
    val occurredAt: Instant,
) : ProjectDomainEvent {
    override val eventType: String = "ProjectRejected"
}

data class ProjectCancelled(
    override val projectId: ProjectId,
    val actorUserId: UserId,
    val reason: String?,
    val occurredAt: Instant,
) : ProjectDomainEvent {
    override val eventType: String = "ProjectCancelled"
}

data class ProjectPublished(
    override val projectId: ProjectId,
    val ownerUserId: UserId,
    val occurredAt: Instant,
) : ProjectDomainEvent {
    override val eventType: String = "ProjectPublished"
}

/**
 * 募集終了の結果イベント（BAT-002）。
 *
 * 成立と不成立は業務上の意味も後続処理も異なるため、別イベントとして発行する
 * （基本設計 §3.2 SUCCEEDED/FAILED、§4.6、§8.1 BAT-003）。
 * 購読側はpayloadを解釈せず eventType だけで振り分けられる。
 */
sealed interface ProjectFundingResult : ProjectDomainEvent {
    val ownerUserId: UserId
    val fundingType: FundingType
    val targetAmount: Long
    val raisedAmount: Long
    val occurredAt: Instant
}

/** 募集成立。精算（SETTLED）へ進む。 */
data class ProjectSucceeded(
    override val projectId: ProjectId,
    override val ownerUserId: UserId,
    override val fundingType: FundingType,
    override val targetAmount: Long,
    override val raisedAmount: Long,
    override val occurredAt: Instant,
) : ProjectFundingResult {
    override val eventType: String = "ProjectSucceeded"
}

/** 募集不成立。BAT-003 返金対象作成の起動契機となる（基本設計 §8.1）。 */
data class ProjectFailed(
    override val projectId: ProjectId,
    override val ownerUserId: UserId,
    override val fundingType: FundingType,
    override val targetAmount: Long,
    override val raisedAmount: Long,
    override val occurredAt: Instant,
) : ProjectFundingResult {
    override val eventType: String = "ProjectFailed"
}
