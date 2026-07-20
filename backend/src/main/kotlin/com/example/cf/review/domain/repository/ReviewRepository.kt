package com.example.cf.review.domain.repository

import com.example.cf.review.domain.model.ReviewRequest
import com.example.cf.review.domain.model.ReviewStatus
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.ReviewId

/**
 * ReviewRequest集約Repository Port。
 */
interface ReviewRepository {
    fun findById(id: ReviewId): ReviewRequest?

    fun findByIdForUpdate(id: ReviewId): ReviewRequest?

    /** アクティブな審査（REQUESTED/UNDER_REVIEW）を対象プロジェクトから取得する。 */
    fun findActiveByProjectId(projectId: ProjectId): ReviewRequest?

    fun findByStatus(status: ReviewStatus): List<ReviewRequest>

    fun save(review: ReviewRequest)
}
