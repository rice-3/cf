package com.example.cf.file.application

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.file.domain.model.FileObject
import com.example.cf.file.domain.model.FilePurpose
import com.example.cf.file.domain.model.FileStatus
import com.example.cf.file.domain.model.Sha256
import com.example.cf.file.domain.repository.FileObjectRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.CurrentUser
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.id.FileId
import com.example.cf.shared.kernel.id.UlidGenerator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val RESOURCE_TYPE = "File"

// ---- コマンド・結果 ---------------------------------------------------------

data class IssueUploadCommand(
    val purpose: FilePurpose,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: Sha256,
)

data class IssueUploadResult(
    val fileId: FileId,
    val uploadUrl: String,
    val headers: Map<String, String>,
    val expiresAt: Instant,
)

data class CompleteUploadCommand(
    val fileId: FileId,
    val sha256: Sha256,
)

data class CompleteUploadResult(
    val fileId: FileId,
    val status: FileStatus,
    val downloadReference: String,
)

// ---- UC-FL-001 署名付きアップロードURL発行（API-FL-001、§6.10） -------------

interface IssueUploadUrlUseCase {
    fun execute(command: IssueUploadCommand, currentUser: CurrentUser, audit: AuditContext): IssueUploadResult
}

@Service
class IssueUploadUrlService(
    private val fileRepository: FileObjectRepository,
    private val storage: FileStoragePort,
    private val properties: FileStorageProperties,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
    private val idGenerator: UlidGenerator,
) : IssueUploadUrlUseCase {

    @Transactional
    override fun execute(
        command: IssueUploadCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): IssueUploadResult {
        val now = clock.instant()
        val fileId = FileId.newId(idGenerator)
        val key = "${properties.keyPrefix}/${currentUser.userId.value}/${fileId.value}/" +
            randomizedName(command.fileName)

        val file = FileObject.issueUpload(
            id = fileId,
            ownerUserId = currentUser.userId,
            purpose = command.purpose,
            originalName = command.fileName,
            contentType = command.contentType,
            sizeBytes = command.sizeBytes,
            sha256 = command.sha256,
            s3Bucket = properties.bucket,
            s3Key = key,
            now = now,
            pendingExpiresAt = now.plus(Duration.ofMinutes(properties.pendingExpiryMinutes)),
        )
        val presigned = storage.presignPutObject(
            bucket = properties.bucket,
            key = key,
            contentType = command.contentType,
            sizeBytes = command.sizeBytes,
            expiry = Duration.ofSeconds(properties.uploadUrlExpirySeconds),
        )

        fileRepository.save(file)
        auditPort.record(audit, "FILE_ISSUE_UPLOAD", RESOURCE_TYPE, fileId.value, "SUCCESS")
        return IssueUploadResult(
            fileId = fileId,
            uploadUrl = presigned.url,
            headers = presigned.headers,
            expiresAt = now.plusSeconds(properties.uploadUrlExpirySeconds),
        )
    }

    /** 元ファイル名は保存キーへ使わず、拡張子のみ引き継いだランダム名にする（§10.2 randomized-name）。 */
    private fun randomizedName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        val base = idGenerator.next().lowercase()
        return if (extension == null) base else "$base.$extension"
    }
}

// ---- UC-FL-002 アップロード完了（API-FL-002、§6.11） ------------------------

interface CompleteUploadUseCase {
    fun execute(command: CompleteUploadCommand, currentUser: CurrentUser, audit: AuditContext): CompleteUploadResult
}

@Service
class CompleteUploadService(
    private val fileRepository: FileObjectRepository,
    private val storage: FileStoragePort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : CompleteUploadUseCase {

    @Transactional
    override fun execute(
        command: CompleteUploadCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): CompleteUploadResult {
        val file = fileRepository.findByIdForUpdate(command.fileId)
        // 他者所有は存在秘匿のため404とする（§6.11 FILE_NOT_FOUND）
        if (file == null || !file.isOwnedBy(currentUser.userId)) {
            throw ResourceNotFoundException("FILE_NOT_FOUND", "File ${command.fileId.value} is not found")
        }

        val head = storage.headObject(file.s3Bucket, file.s3Key)
        file.completeUpload(head, command.sha256, clock.instant())

        fileRepository.save(file)
        auditPort.record(audit, "FILE_COMPLETE_UPLOAD", RESOURCE_TYPE, file.id.value, "SUCCESS")
        return CompleteUploadResult(
            fileId = file.id,
            status = file.status,
            downloadReference = "s3://${file.s3Bucket}/${file.s3Key}",
        )
    }
}
