package com.example.cf.testsupport

import com.example.cf.file.domain.model.FileObject
import com.example.cf.file.domain.model.FilePurpose
import com.example.cf.file.domain.model.Sha256
import com.example.cf.project.domain.model.FundingCondition
import com.example.cf.project.domain.model.FundingType
import com.example.cf.project.domain.model.Project
import com.example.cf.project.domain.model.ProjectBody
import com.example.cf.project.domain.model.ProjectStatus
import com.example.cf.project.domain.model.ProjectSummary
import com.example.cf.project.domain.model.ProjectTitle
import com.example.cf.project.domain.model.RewardPlan
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.id.FileId
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.RewardPlanId
import com.example.cf.shared.kernel.id.UserId
import com.example.cf.shared.kernel.money.Money
import com.example.cf.shared.kernel.time.DateRange
import com.github.f4b6a3.ulid.UlidCreator
import java.time.Instant

/**
 * ドメイン上有効な標準テストデータ（詳細設計 §14.5 Object Mother）。
 * 異常値は本Builderを部分変更して作成する。実在情報は使用しない。
 */
object Fixtures {

    /** 固定基準時刻（§14.5: 時刻依存テストはFixedClockを使用）。 */
    val NOW: Instant = Instant.parse("2026-07-20T00:00:00Z")

    fun newUlid(): String = UlidCreator.getMonotonicUlid().toString()

    fun userId() = UserId(newUlid())

    fun projectId() = ProjectId(newUlid())

    fun fileId() = FileId(newUlid())

    fun fundingCondition(
        targetAmount: Long = 500_000,
        fundingType: FundingType = FundingType.ALL_OR_NOTHING,
        start: Instant = NOW.plusSeconds(24 * 3600),
        end: Instant = NOW.plusSeconds(31L * 24 * 3600),
    ) = FundingCondition(Money.of(targetAmount), fundingType, DateRange(start, end))

    fun rewardPlan(
        name: String = "サンクスメール",
        unitAmount: Long = 3_000,
        quantityLimit: Int? = null,
        displayOrder: Int = 1,
    ) = RewardPlan.create(
        id = RewardPlanId(newUlid()),
        name = name,
        description = "お礼のメールをお送りします。",
        unitAmount = Money.of(unitAmount),
        quantityLimit = quantityLimit,
        displayOrder = displayOrder,
    )

    /** テスト用SHA-256（64桁hex）。 */
    const val SHA256_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    fun pendingFileObject(
        owner: UserId = userId(),
        purpose: FilePurpose = FilePurpose.PROJECT_MAIN,
        contentType: String = "image/jpeg",
        sizeBytes: Long = 204_800,
        sha256: String = SHA256_A,
    ) = FileObject.issueUpload(
        id = fileId(),
        ownerUserId = owner,
        purpose = purpose,
        originalName = "main.jpg",
        contentType = contentType,
        sizeBytes = sizeBytes,
        sha256 = Sha256(sha256),
        s3Bucket = "cf-test-files",
        s3Key = "test/${owner.value}/${newUlid()}/main.jpg",
        now = NOW,
        pendingExpiresAt = NOW.plusSeconds(30 * 60),
    )

    fun draftProject(
        id: ProjectId = projectId(),
        owner: UserId = userId(),
        status: ProjectStatus = ProjectStatus.DRAFT,
        mainFileId: FileId? = fileId(),
        rewardPlans: List<RewardPlan> = listOf(rewardPlan()),
        fundingCondition: FundingCondition = fundingCondition(),
        version: Long = 0,
    ) = Project(
        id = id,
        ownerUserId = owner,
        title = ProjectTitle("テスト用プロジェクト"),
        summary = ProjectSummary("教育用のサンプルプロジェクトです。"),
        body = ProjectBody("これは教育用クラウドファンディングの本文です。"),
        fundingCondition = fundingCondition,
        rewardPlans = rewardPlans,
        status = status,
        mainFileId = mainFileId,
        version = Version(version),
        createdAt = NOW,
        updatedAt = NOW,
    )
}
