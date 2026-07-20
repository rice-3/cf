package com.example.cf.project.adapter.`in`.web

import com.example.cf.project.application.query.ProjectDetailView
import com.example.cf.project.application.query.ProjectListItem
import com.example.cf.project.application.query.ProjectSearchQuery
import com.example.cf.project.domain.model.ProjectStatus
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.RoleCode
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.web.ApiEnvelope
import com.example.cf.shared.web.CorrelationIdFilter
import com.example.cf.shared.web.CurrentUserSupport
import com.example.cf.shared.web.toEnvelope
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/** 公開後に一般公開される状態（SCR-011）。 */
private val PUBLIC_STATUSES = setOf(
    ProjectStatus.PUBLISHED,
    ProjectStatus.SUCCEEDED,
    ProjectStatus.FAILED,
    ProjectStatus.REFUNDING,
    ProjectStatus.REFUNDED,
    ProjectStatus.SETTLED,
)

/** 全プロジェクトを参照できる業務ロール（基本設計 §2.2）。 */
private val STAFF_ROLES = setOf(RoleCode.REVIEWER, RoleCode.OPERATOR, RoleCode.ADMIN, RoleCode.AUDITOR)

/**
 * 公開プロジェクトAPI（API-PJ-001/002、基本設計 §6.4）。
 */
@RestController
@RequestMapping("/api/v1/projects")
class PublicProjectController(
    private val searchQuery: ProjectSearchQuery,
    private val userSupport: CurrentUserSupport,
    private val clock: Clock,
) {

    /** API-PJ-001 公開プロジェクト検索（SCR-010）。 */
    @GetMapping
    fun search(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): ApiEnvelope<PageResult<ProjectListItem>> {
        val safeSize = size.coerceIn(1, 100)
        val safePage = page.coerceAtLeast(0)
        return searchQuery.searchPublished(keyword, safePage, safeSize)
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /**
     * API-PJ-002 詳細取得（SCR-011）。
     * 非公開状態は所有者と業務ロールのみ参照可。それ以外は404で情報を過剰開示しない（§11.1）。
     */
    @GetMapping("/{projectId}")
    fun detail(
        @PathVariable projectId: String,
        request: HttpServletRequest,
    ): ApiEnvelope<ProjectDetailView> {
        val id = parseProjectId(projectId)
        val detail = searchQuery.findDetail(id)
            ?: throw ResourceNotFoundException("PROJECT_NOT_FOUND", "Project $projectId is not found")

        if (detail.status !in PUBLIC_STATUSES) {
            val currentUser = userSupport.findCurrentUser()
            val isOwner = currentUser?.userId?.value == detail.ownerUserId
            val isStaff = currentUser?.roles?.any { it in STAFF_ROLES } == true
            if (!isOwner && !isStaff) {
                throw ResourceNotFoundException("PROJECT_NOT_FOUND", "Project $projectId is not found")
            }
        }
        return detail.toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }
}
