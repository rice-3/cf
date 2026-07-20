package com.example.cf.project.domain.model

/**
 * プロジェクト状態（基本設計 §3.2）。
 * 遷移可否は [canTransitionTo] で表現し、集約メソッド以外から状態を書き換えない。
 */
enum class ProjectStatus {
    DRAFT,
    REVIEW_REQUESTED,
    UNDER_REVIEW,
    RETURNED,
    APPROVED,
    PUBLISHED,
    SUSPENDED,
    SUCCEEDED,
    FAILED,
    REFUNDING,
    REFUNDED,
    SETTLED,
    REJECTED,
    CANCELLED,
    ;

    fun canTransitionTo(next: ProjectStatus): Boolean = next in allowedTransitions()

    private fun allowedTransitions(): Set<ProjectStatus> = when (this) {
        DRAFT -> setOf(REVIEW_REQUESTED, CANCELLED)
        REVIEW_REQUESTED -> setOf(UNDER_REVIEW, CANCELLED)
        UNDER_REVIEW -> setOf(APPROVED, RETURNED, REJECTED)
        RETURNED -> setOf(REVIEW_REQUESTED, CANCELLED)
        APPROVED -> setOf(PUBLISHED, CANCELLED)
        PUBLISHED -> setOf(SUCCEEDED, FAILED, SUSPENDED)
        SUSPENDED -> setOf(PUBLISHED, CANCELLED)
        SUCCEEDED -> setOf(SETTLED)
        FAILED -> setOf(REFUNDING)
        REFUNDING -> setOf(REFUNDED)
        // REJECTED / CANCELLED / REFUNDED / SETTLED は終端状態（§3.3）
        REJECTED, CANCELLED, REFUNDED, SETTLED -> emptySet()
    }

    /** 起案者が内容を編集できる状態か。 */
    val editable: Boolean get() = this == DRAFT || this == RETURNED
}
