package com.example.cf.project.domain.model

import com.example.cf.project.domain.event.ProjectFailed
import com.example.cf.project.domain.event.ProjectSucceeded
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.error.AccessDeniedException
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.error.OptimisticLockConflictException
import com.example.cf.shared.kernel.id.ReviewId
import com.example.cf.shared.kernel.money.Money
import com.example.cf.shared.kernel.time.DateRange
import com.example.cf.testsupport.Fixtures
import com.example.cf.testsupport.Fixtures.NOW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * Project集約の単体テスト（詳細設計 §14.2 PJ-U系）。
 */
class ProjectTest : FunSpec({

    fun reviewId() = ReviewId(Fixtures.newUlid())

    context("作成（PJ-U-001/002/003）") {
        test("有効な下書き作成でDRAFTとProjectCreatedイベント") {
            val (project, event) = Project.create(
                id = Fixtures.projectId(),
                ownerUserId = Fixtures.userId(),
                title = ProjectTitle("タイトル"),
                summary = ProjectSummary("概要"),
                body = ProjectBody("本文"),
                fundingCondition = Fixtures.fundingCondition(),
                rewardPlans = listOf(Fixtures.rewardPlan()),
                mainFileId = Fixtures.fileId(),
                now = NOW,
            )
            project.status shouldBe ProjectStatus.DRAFT
            project.version shouldBe Version(0)
            event.eventType shouldBe "ProjectCreated"
            event.projectId shouldBe project.id
        }

        test("目標金額999円は生成できない（PJ-U-002）") {
            shouldThrow<IllegalArgumentException> {
                Fixtures.fundingCondition(targetAmount = 999)
            }
        }

        test("目標金額100,000,001円は生成できない") {
            shouldThrow<IllegalArgumentException> {
                Fixtures.fundingCondition(targetAmount = 100_000_001)
            }
        }

        test("終了＝開始の期間は生成できない（PJ-U-003）") {
            val t = NOW.plusSeconds(3600)
            shouldThrow<IllegalArgumentException> { DateRange(t, t) }
        }

        test("181日の募集期間は生成できない") {
            shouldThrow<IllegalArgumentException> {
                Fixtures.fundingCondition(
                    start = NOW,
                    end = NOW.plusSeconds(181L * 24 * 3600),
                )
            }
        }
    }

    context("審査申請（PJ-U-004/006）") {
        test("DRAFTから審査申請できる") {
            val project = Fixtures.draftProject()
            val event = project.submitForReview(reviewId(), NOW)
            project.status shouldBe ProjectStatus.REVIEW_REQUESTED
            project.version shouldBe Version(1)
            event.eventType shouldBe "ProjectSubmittedForReview"
        }

        test("RETURNEDから再申請できる") {
            val project = Fixtures.draftProject(status = ProjectStatus.RETURNED)
            project.submitForReview(reviewId(), NOW)
            project.status shouldBe ProjectStatus.REVIEW_REQUESTED
        }

        test("PUBLISHEDからは審査申請できない") {
            val project = Fixtures.draftProject(status = ProjectStatus.PUBLISHED)
            val ex = shouldThrow<InvalidStateException> {
                project.submitForReview(reviewId(), NOW)
            }
            ex.errorCode shouldBe "PROJECT_INVALID_STATE"
        }
    }

    context("下書き更新（PJ-U-006）") {
        test("PUBLISHEDの更新はPROJECT_INVALID_STATE") {
            val project = Fixtures.draftProject(status = ProjectStatus.PUBLISHED)
            val ex = shouldThrow<InvalidStateException> {
                project.updateDraft(
                    title = ProjectTitle("新タイトル"),
                    summary = ProjectSummary("新概要"),
                    body = ProjectBody("新本文"),
                    fundingCondition = Fixtures.fundingCondition(),
                    rewardPlans = listOf(Fixtures.rewardPlan()),
                    mainFileId = Fixtures.fileId(),
                    now = NOW,
                )
            }
            ex.errorCode shouldBe "PROJECT_INVALID_STATE"
        }

        test("DRAFTの更新でversionが増分される") {
            val project = Fixtures.draftProject()
            project.updateDraft(
                title = ProjectTitle("新タイトル"),
                summary = ProjectSummary("新概要"),
                body = ProjectBody("新本文"),
                fundingCondition = Fixtures.fundingCondition(),
                rewardPlans = listOf(Fixtures.rewardPlan()),
                mainFileId = project.mainFileId,
                now = NOW.plusSeconds(60),
            )
            project.title.value shouldBe "新タイトル"
            project.version shouldBe Version(1)
        }
    }

    context("審査フロー状態遷移") {
        test("REVIEW_REQUESTED→UNDER_REVIEW→APPROVED") {
            val project = Fixtures.draftProject(status = ProjectStatus.REVIEW_REQUESTED)
            val rid = reviewId()
            val reviewer = Fixtures.userId()
            project.startReview(rid, reviewer, NOW)
            project.status shouldBe ProjectStatus.UNDER_REVIEW
            project.approve(rid, reviewer, NOW)
            project.status shouldBe ProjectStatus.APPROVED
        }

        test("差戻しはコメント必須") {
            val project = Fixtures.draftProject(status = ProjectStatus.UNDER_REVIEW)
            shouldThrow<IllegalArgumentException> {
                project.returnForCorrection(reviewId(), Fixtures.userId(), " ", NOW)
            }
        }

        test("UNDER_REVIEW→REJECTEDは終端でDRAFTへ戻せない") {
            val project = Fixtures.draftProject(status = ProjectStatus.UNDER_REVIEW)
            project.reject(reviewId(), Fixtures.userId(), "LEGAL_VIOLATION", NOW)
            project.status shouldBe ProjectStatus.REJECTED
            shouldThrow<InvalidStateException> {
                project.submitForReview(reviewId(), NOW)
            }
        }
    }

    context("公開・募集終了") {
        test("APPROVEDかつ開始日時到達で公開できる") {
            val project = Fixtures.draftProject(
                status = ProjectStatus.APPROVED,
                fundingCondition = Fixtures.fundingCondition(
                    start = NOW.minusSeconds(60),
                    end = NOW.plusSeconds(30L * 24 * 3600),
                ),
            )
            project.publish(NOW)
            project.status shouldBe ProjectStatus.PUBLISHED
        }

        test("開始日時前は公開できない") {
            val project = Fixtures.draftProject(status = ProjectStatus.APPROVED)
            shouldThrow<InvalidStateException> { project.publish(NOW) }
        }

        test("All-or-Nothing: 目標到達でSUCCEEDEDとProjectSucceeded") {
            val owner = Fixtures.userId()
            val project = publishedProjectEndingAt(NOW.minusSeconds(1), owner = owner)
            val event = project.closeFunding(NOW, Money.of(500_000))
            project.status shouldBe ProjectStatus.SUCCEEDED
            event.shouldBeInstanceOf<ProjectSucceeded>()
            event.eventType shouldBe "ProjectSucceeded"
            event.raisedAmount shouldBe 500_000
            // 起案者向け通知の宛先解決のためownerUserIdを保持する（ADR-0002）
            event.ownerUserId shouldBe owner
        }

        test("All-or-Nothing: 目標未達でFAILEDとProjectFailed") {
            val project = publishedProjectEndingAt(NOW.minusSeconds(1))
            val event = project.closeFunding(NOW, Money.of(499_999))
            project.status shouldBe ProjectStatus.FAILED
            // BAT-003 返金対象作成はこのeventTypeだけで購読する（基本設計 §8.1）
            event.shouldBeInstanceOf<ProjectFailed>()
            event.eventType shouldBe "ProjectFailed"
        }

        test("All-In: 1円でも集まればSUCCEEDED") {
            val project = publishedProjectEndingAt(
                NOW.minusSeconds(1),
                fundingType = FundingType.ALL_IN,
            )
            val event = project.closeFunding(NOW, Money.of(1))
            project.status shouldBe ProjectStatus.SUCCEEDED
            event.shouldBeInstanceOf<ProjectSucceeded>()
        }

        test("All-In: 支援ゼロならFAILED") {
            val project = publishedProjectEndingAt(
                NOW.minusSeconds(1),
                fundingType = FundingType.ALL_IN,
            )
            val event = project.closeFunding(NOW, Money.ZERO)
            project.status shouldBe ProjectStatus.FAILED
            event.shouldBeInstanceOf<ProjectFailed>()
        }

        test("募集期間中はcloseFundingできない") {
            val project = publishedProjectEndingAt(NOW.plusSeconds(3600))
            shouldThrow<InvalidStateException> {
                project.closeFunding(NOW, Money.of(500_000))
            }
        }
    }

    context("認可・楽観ロックガード") {
        test("所有者以外はAccessDenied") {
            val project = Fixtures.draftProject()
            shouldThrow<AccessDeniedException> {
                project.requireOwnedBy(Fixtures.userId())
            }
        }

        test("version不一致はOptimisticLockConflict（PJ-I-001相当）") {
            val project = Fixtures.draftProject(version = 3)
            shouldThrow<OptimisticLockConflictException> {
                project.requireVersion(Version(2))
            }
        }
    }
})

private fun publishedProjectEndingAt(
    end: Instant,
    fundingType: FundingType = FundingType.ALL_OR_NOTHING,
    owner: com.example.cf.shared.kernel.id.UserId = Fixtures.userId(),
) = Fixtures.draftProject(
    owner = owner,
    status = ProjectStatus.PUBLISHED,
    fundingCondition = Fixtures.fundingCondition(
        fundingType = fundingType,
        start = end.minusSeconds(30L * 24 * 3600),
        end = end,
    ),
)
