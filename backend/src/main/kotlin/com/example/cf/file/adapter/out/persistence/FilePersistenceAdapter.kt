package com.example.cf.file.adapter.out.persistence

import com.example.cf.file.application.FileReferenceQuery
import com.example.cf.file.domain.model.FileObject
import com.example.cf.file.domain.model.FilePurpose
import com.example.cf.file.domain.model.FileStatus
import com.example.cf.file.domain.model.Sha256
import com.example.cf.file.domain.repository.FileObjectRepository
import com.example.cf.shared.kernel.id.FileId
import com.example.cf.shared.kernel.id.ULID_PATTERN
import com.example.cf.shared.kernel.id.UserId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Instant

/** file_object テーブル（詳細設計 §8.15）。 */
@Entity
@Table(name = "file_object")
class FileJpaEntity(
    @Id
    @Column(name = "file_id", length = 26)
    var fileId: String = "",

    @Column(name = "owner_user_id", length = 26, nullable = false)
    var ownerUserId: String = "",

    @Column(name = "purpose", length = 50, nullable = false)
    var purpose: String = "",

    @Column(name = "s3_bucket", length = 63, nullable = false)
    var s3Bucket: String = "",

    @Column(name = "s3_key", length = 1024, nullable = false)
    var s3Key: String = "",

    @Column(name = "original_name", length = 255, nullable = false)
    var originalName: String = "",

    @Column(name = "content_type", length = 100, nullable = false)
    var contentType: String = "",

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0,

    @Column(name = "sha256", length = 64, nullable = false)
    var sha256: String = "",

    @Column(name = "status", length = 30, nullable = false)
    var status: String = "",

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

@Repository
interface FileJpaRepository : JpaRepository<FileJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FileJpaEntity f where f.fileId = :id")
    fun findWithLockByFileId(@Param("id") id: String): FileJpaEntity?

    fun existsByFileIdAndOwnerUserIdAndStatus(fileId: String, ownerUserId: String, status: String): Boolean

    /** BAT-008の対象。多重起動時も同一行を二重処理しない（基本設計 §8.3）。 */
    @Query(
        value = """
            select * from file_object
             where status = 'PENDING' and expires_at is not null and expires_at < :now
             order by expires_at
             for update skip locked
             limit :limit
        """,
        nativeQuery = true,
    )
    fun lockExpiredPendingBatch(
        @Param("now") now: java.time.Instant,
        @Param("limit") limit: Int,
    ): List<FileJpaEntity>
}

/**
 * File永続化Adapter（Repository / 公開契約FileReferenceQueryの実装）。
 * 第1段階のStubFileReferenceQueryを置き換える実検証実装。
 */
@Component
class FilePersistenceAdapter(
    private val jpaRepository: FileJpaRepository,
) : FileObjectRepository,
    FileReferenceQuery {

    // ---- FileObjectRepository ------------------------------------------------

    override fun findById(id: FileId): FileObject? = jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByIdForUpdate(id: FileId): FileObject? = jpaRepository.findWithLockByFileId(id.value)?.toDomain()

    override fun lockExpiredPendingBatch(now: java.time.Instant, limit: Int): List<FileObject> = jpaRepository.lockExpiredPendingBatch(now, limit).map { it.toDomain() }

    override fun save(file: FileObject) {
        val entity = jpaRepository.findById(file.id.value).orElse(FileJpaEntity())
        entity.fileId = file.id.value
        entity.ownerUserId = file.ownerUserId.value
        entity.purpose = file.purpose.name
        entity.s3Bucket = file.s3Bucket
        entity.s3Key = file.s3Key
        entity.originalName = file.originalName
        entity.contentType = file.contentType
        entity.sizeBytes = file.sizeBytes
        entity.sha256 = file.sha256.value
        entity.status = file.status.name
        entity.expiresAt = file.expiresAt
        entity.createdAt = file.createdAt
        entity.updatedAt = file.updatedAt
        jpaRepository.save(entity)
    }

    private fun FileJpaEntity.toDomain() = FileObject(
        id = FileId(fileId),
        ownerUserId = UserId(ownerUserId),
        purpose = FilePurpose.valueOf(purpose),
        s3Bucket = s3Bucket,
        s3Key = s3Key,
        originalName = originalName,
        contentType = contentType,
        sizeBytes = sizeBytes,
        sha256 = Sha256(sha256),
        status = FileStatus.valueOf(status),
        expiresAt = expiresAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    // ---- FileReferenceQuery（公開契約、基本設計 §4.1） -----------------------

    override fun isCompletedAndOwnedBy(fileId: String, ownerUserId: String): Boolean = ULID_PATTERN.matches(fileId) &&
        jpaRepository.existsByFileIdAndOwnerUserIdAndStatus(fileId, ownerUserId, FileStatus.COMPLETE.name)
}
