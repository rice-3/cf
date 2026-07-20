package com.example.cf.project.domain.repository

import com.example.cf.project.domain.model.Project
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.UserId
import java.time.Instant

/**
 * Project集約Repository Port（要件定義 §4.4-5: インターフェースはドメイン側に置く）。
 */
interface ProjectRepository {
    fun findById(id: ProjectId): Project?

    /** 更新目的の取得（実装側で悲観 or 楽観ロックを適用）。 */
    fun findByIdForUpdate(id: ProjectId): Project?

    fun findByOwner(ownerUserId: UserId): List<Project>

    /**
     * BAT-001 公開開始の対象（APPROVEDかつ開始日時到達）を
     * `FOR UPDATE SKIP LOCKED` で取得する（基本設計 §8.3、詳細設計 §9）。
     */
    fun lockPublishTargets(now: Instant, limit: Int): List<Project>

    /** BAT-002 募集終了の対象（PUBLISHEDかつ終了日時到達）を同様に取得する。 */
    fun lockFundingCloseTargets(now: Instant, limit: Int): List<Project>

    fun save(project: Project)
}
