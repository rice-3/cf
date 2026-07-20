package com.example.cf.review.domain.model

import com.example.cf.shared.kernel.error.BusinessRuleViolationException
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.ReviewId
import com.example.cf.testsupport.Fixtures
import com.example.cf.testsupport.Fixtures.NOW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReviewRequestTest : FunSpec({

    fun newReview() = ReviewRequest.create(
        id = ReviewId(Fixtures.newUlid()),
        projectId = ProjectId(Fixtures.newUlid()),
        submittedAt = NOW,
    )

    fun completedChecklist() = ReviewChecklist(
        ReviewChecklist.REQUIRED_ITEMS.associateWith { true },
    )

    test("作成時はREQUESTEDで担当者なし") {
        val review = newReview()
        review.status shouldBe ReviewStatus.REQUESTED
        review.reviewerUserId shouldBe null
    }

    test("startで担当者が割り当てられUNDER_REVIEWへ") {
        val review = newReview()
        val reviewer = Fixtures.userId()
        review.start(reviewer, NOW)
        review.status shouldBe ReviewStatus.UNDER_REVIEW
        review.reviewerUserId shouldBe reviewer
        review.startedAt shouldBe NOW
    }

    test("開始済みの審査は再startできない（REVIEW_ALREADY_ASSIGNED）") {
        val review = newReview()
        review.start(Fixtures.userId(), NOW)
        val ex = shouldThrow<InvalidStateException> {
            review.start(Fixtures.userId(), NOW)
        }
        ex.errorCode shouldBe "REVIEW_ALREADY_ASSIGNED"
    }

    test("チェックリスト完了で承認できる") {
        val review = newReview()
        review.start(Fixtures.userId(), NOW)
        review.approve(completedChecklist(), NOW)
        review.status shouldBe ReviewStatus.APPROVED
        review.completedAt shouldBe NOW
    }

    test("チェックリスト未完了の承認はREVIEW_CHECKLIST_INCOMPLETE") {
        val review = newReview()
        review.start(Fixtures.userId(), NOW)
        val incomplete = ReviewChecklist(mapOf("CONTENT_CONFIRMED" to true))
        val ex = shouldThrow<BusinessRuleViolationException> {
            review.approve(incomplete, NOW)
        }
        ex.errorCode shouldBe "REVIEW_CHECKLIST_INCOMPLETE"
    }

    test("REQUESTEDのまま承認はできない") {
        val review = newReview()
        val ex = shouldThrow<InvalidStateException> {
            review.approve(completedChecklist(), NOW)
        }
        ex.errorCode shouldBe "REVIEW_INVALID_STATE"
    }

    test("差戻しはコメント必須（REVIEW_COMMENT_REQUIRED）") {
        val review = newReview()
        review.start(Fixtures.userId(), NOW)
        val ex = shouldThrow<BusinessRuleViolationException> {
            review.returnForCorrection("  ", NOW)
        }
        ex.errorCode shouldBe "REVIEW_COMMENT_REQUIRED"
    }

    test("却下は理由区分とコメントで実行できる") {
        val review = newReview()
        review.start(Fixtures.userId(), NOW)
        review.reject(RejectReasonCode.INSUFFICIENT_INFORMATION, "情報不足のため", NOW)
        review.status shouldBe ReviewStatus.REJECTED
    }
})
