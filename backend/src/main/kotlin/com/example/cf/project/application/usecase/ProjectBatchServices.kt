package com.example.cf.project.application.usecase

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.funding.application.SupportReferenceQuery
import com.example.cf.project.domain.repository.ProjectRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.money.Money
import com.example.cf.shared.outbox.OutboxAppendPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private const val RESOURCE_TYPE = "Project"

/**
 * BAT-001 公開開始処理（基本設計 §8.1）。
 * 承認済みかつ開始時刻到達の案件をPUBLISHEDへ遷移させる。
 */
interface PublishApprovedProjectsUseCase {
    /** @return 公開した件数 */
    fun execute(limit: Int, audit: AuditContext): Int
}

@Service
class PublishApprovedProjectsService(
    private val projectRepository: ProjectRepository,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : PublishApprovedProjectsUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun execute(limit: Int, audit: AuditContext): Int {
        val now = clock.instant()
        val targets = projectRepository.lockPublishTargets(now, limit)
        targets.forEach { project ->
            val event = project.publish(now)
            projectRepository.save(project)
            outbox.append(event, audit.correlationId)
            auditPort.record(audit, "PROJECT_PUBLISH", RESOURCE_TYPE, project.id.value, "SUCCESS")
        }
        if (targets.isNotEmpty()) {
            log.info("BAT-001 published {} projects", targets.size)
        }
        return targets.size
    }
}

/**
 * BAT-002 募集終了処理（基本設計 §8.1）。
 * 終了時刻到達案件の成立・不成立を判定し、ProjectSucceeded／ProjectFailedを発行する。
 * 集まった金額はFundingの公開契約経由で取得する（supportテーブルを直接参照しない）。
 */
interface CloseFundingUseCase {
    /** @return 判定した件数 */
    fun execute(limit: Int, audit: AuditContext): Int
}

@Service
class CloseFundingService(
    private val projectRepository: ProjectRepository,
    private val supportReferenceQuery: SupportReferenceQuery,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : CloseFundingUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun execute(limit: Int, audit: AuditContext): Int {
        val now = clock.instant()
        val targets = projectRepository.lockFundingCloseTargets(now, limit)
        targets.forEach { project ->
            val raised = Money.of(supportReferenceQuery.sumPaidAmount(project.id))
            val event = project.closeFunding(now, raised)
            projectRepository.save(project)
            outbox.append(event, audit.correlationId)
            auditPort.record(audit, "PROJECT_FUNDING_CLOSE", RESOURCE_TYPE, project.id.value, "SUCCESS")
            log.info(
                "BAT-002 closed funding: projectId={} status={} raised={}",
                project.id.value,
                project.status,
                raised.amount,
            )
        }
        return targets.size
    }
}
