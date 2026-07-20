package com.example.cf.file.application

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.file.domain.repository.FileObjectRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.error.DependencyException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * BAT-008 ファイル清掃（基本設計 §8.1）。
 * 未完了・期限切れのアップロードを削除する。
 */
interface FileCleanupUseCase {
    /** @return 削除した件数 */
    fun execute(limit: Int, audit: AuditContext): Int
}

@Service
class FileCleanupService(
    private val fileRepository: FileObjectRepository,
    private val storage: FileStoragePort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : FileCleanupUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun execute(limit: Int, audit: AuditContext): Int {
        val now = clock.instant()
        val targets = fileRepository.lockExpiredPendingBatch(now, limit)
        var deleted = 0

        targets.forEach { file ->
            // S3削除が失敗しても他の対象の処理は継続する（次回実行で再試行される）
            val storageDeleted = runCatching { storage.deleteObject(file.s3Bucket, file.s3Key) }
                .onFailure { e ->
                    if (e is DependencyException) {
                        log.warn("BAT-008 failed to delete object for file {}", file.id.value)
                    } else {
                        throw e
                    }
                }
                .isSuccess
            if (storageDeleted) {
                file.delete(now)
                fileRepository.save(file)
                auditPort.record(audit, "FILE_CLEANUP", "File", file.id.value, "SUCCESS")
                deleted += 1
            }
        }

        if (deleted > 0) {
            log.info("BAT-008 cleaned up {} expired uploads", deleted)
        }
        return deleted
    }
}
