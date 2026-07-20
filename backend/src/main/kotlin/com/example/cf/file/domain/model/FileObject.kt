package com.example.cf.file.domain.model

import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.kernel.id.FileId
import com.example.cf.shared.kernel.id.UserId
import java.time.Instant

/** ファイル用途（詳細設計 §6.10）。 */
enum class FilePurpose {
    PROJECT_MAIN,
    PROJECT_ATTACHMENT,
}

/** ファイル状態（詳細設計 §8.15）。 */
enum class FileStatus {
    PENDING,
    COMPLETE,
    DELETED,
}

/** SHA-256ハッシュ（64桁hex、§6.10）。小文字へ正規化して保持する。 */
@JvmInline
value class Sha256(val value: String) {
    init {
        require(PATTERN.matches(value)) { "sha256 must be 64 lowercase hex characters" }
    }

    companion object {
        private val PATTERN = Regex("[0-9a-f]{64}")

        fun of(raw: String): Sha256 = Sha256(raw.lowercase())
    }
}

/** S3 HeadObjectの実測値（§6.11 照合用）。 */
data class StoredObjectHead(
    val sizeBytes: Long,
    val contentType: String,
)

/**
 * FileObject集約ルート（詳細設計 §4.7）。
 * PENDING（URL発行済み）→ COMPLETE（実体照合済み）→ DELETED。
 */
class FileObject(
    val id: FileId,
    val ownerUserId: UserId,
    val purpose: FilePurpose,
    val s3Bucket: String,
    val s3Key: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: Sha256,
    status: FileStatus,
    expiresAt: Instant?,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var status: FileStatus = status
        private set
    var expiresAt: Instant? = expiresAt
        private set
    var updatedAt: Instant = updatedAt
        private set

    val isComplete: Boolean get() = status == FileStatus.COMPLETE

    fun isOwnedBy(userId: UserId): Boolean = ownerUserId == userId

    /**
     * アップロード完了（§4.7 completeUpload、§6.11）。
     * HeadObject実測値と発行時申告値を照合してCOMPLETEへ遷移する。
     * 同一ハッシュでの再実行は冪等に成功する。
     */
    fun completeUpload(head: StoredObjectHead?, requestSha256: Sha256, now: Instant) {
        if (requestSha256 != sha256) {
            throw InvalidStateException(
                "FILE_METADATA_MISMATCH",
                "sha256 does not match the issued value for file ${id.value}",
            )
        }
        if (status == FileStatus.COMPLETE) {
            return // 冪等（§6.11: 同一ハッシュなら再実行可）
        }
        if (status == FileStatus.DELETED) {
            throw InvalidStateException(
                "FILE_INVALID_STATE",
                "File ${id.value} is deleted and cannot be completed",
            )
        }
        val expiry = expiresAt
        if (expiry != null && !now.isBefore(expiry)) {
            throw InvalidStateException(
                "FILE_UPLOAD_EXPIRED",
                "Upload for file ${id.value} is expired",
            )
        }
        if (head == null) {
            throw InvalidStateException(
                "FILE_METADATA_MISMATCH",
                "Uploaded object is not found for file ${id.value}",
            )
        }
        if (head.sizeBytes != sizeBytes || !head.contentType.equals(contentType, ignoreCase = true)) {
            throw InvalidStateException(
                "FILE_METADATA_MISMATCH",
                "Uploaded object does not match the issued metadata for file ${id.value}",
            )
        }
        status = FileStatus.COMPLETE
        expiresAt = null
        updatedAt = now
    }

    /** 削除（§4.7 delete）。参照有無の検証は呼出し側UseCaseで行う。 */
    fun delete(now: Instant) {
        if (status == FileStatus.DELETED) {
            return
        }
        status = FileStatus.DELETED
        updatedAt = now
    }

    companion object {
        /** 許可MIME（§6.10）。 */
        val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp", "application/pdf")

        /** 最大10MB（§4.7 issueUpload）。 */
        const val MAX_SIZE_BYTES: Long = 10L * 1024 * 1024

        /** アップロードURL発行時の生成（§4.7 issueUpload、§6.10 検証）。 */
        fun issueUpload(
            id: FileId,
            ownerUserId: UserId,
            purpose: FilePurpose,
            originalName: String,
            contentType: String,
            sizeBytes: Long,
            sha256: Sha256,
            s3Bucket: String,
            s3Key: String,
            now: Instant,
            pendingExpiresAt: Instant,
        ): FileObject {
            if (originalName.isEmpty() || originalName.length > 255 ||
                originalName.any { it == '/' || it == '\\' || it.isISOControl() }
            ) {
                throw ValidationException(message = "fileName must be 1..255 characters without path separators")
            }
            if (contentType !in ALLOWED_CONTENT_TYPES) {
                throw ValidationException(
                    errorCode = "FILE_TYPE_NOT_ALLOWED",
                    message = "contentType is not allowed: $contentType",
                )
            }
            if (sizeBytes < 1) {
                throw ValidationException(message = "size must be a positive integer")
            }
            if (sizeBytes > MAX_SIZE_BYTES) {
                throw ValidationException(
                    errorCode = "FILE_TOO_LARGE",
                    message = "size must be <= $MAX_SIZE_BYTES bytes",
                )
            }
            return FileObject(
                id = id,
                ownerUserId = ownerUserId,
                purpose = purpose,
                s3Bucket = s3Bucket,
                s3Key = s3Key,
                originalName = originalName,
                contentType = contentType,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                status = FileStatus.PENDING,
                expiresAt = pendingExpiresAt,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
