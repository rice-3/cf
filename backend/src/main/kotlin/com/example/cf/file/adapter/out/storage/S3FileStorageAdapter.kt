package com.example.cf.file.adapter.out.storage

import com.example.cf.file.application.FileStoragePort
import com.example.cf.file.application.PresignedUpload
import com.example.cf.file.domain.model.StoredObjectHead
import com.example.cf.shared.kernel.error.DependencyException
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration

/**
 * 実S3 Adapter（詳細設計 §10.2）。dev以上の環境で使用する。
 * 認証情報・リージョンはAWS SDK既定チェーン（ECSタスクロール等、§13.1）で解決する。
 * Bucket側はBlock Public Access有効・SSE暗号化を前提とする（§10.2、Terraformで構成）。
 */
@Component
@Profile("!local & !test")
class S3FileStorageAdapter : FileStoragePort, DisposableBean {

    private val s3: S3Client = S3Client.create()
    private val presigner: S3Presigner = S3Presigner.create()

    override fun presignPutObject(
        bucket: String,
        key: String,
        contentType: String,
        sizeBytes: Long,
        expiry: Duration,
    ): PresignedUpload {
        val putObject = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType) // Content-Typeを署名条件に含める（§10.2）
            .contentLength(sizeBytes)
            .build()
        val presigned = try {
            presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                    .signatureDuration(expiry)
                    .putObjectRequest(putObject)
                    .build(),
            )
        } catch (e: SdkException) {
            throw DependencyException("S3_UNAVAILABLE", "Failed to presign upload URL", e)
        }
        return PresignedUpload(
            url = presigned.url().toString(),
            headers = presigned.signedHeaders().mapValues { it.value.first() },
        )
    }

    override fun headObject(bucket: String, key: String): StoredObjectHead? = try {
        val head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        StoredObjectHead(sizeBytes = head.contentLength(), contentType = head.contentType() ?: "")
    } catch (e: NoSuchKeyException) {
        null
    } catch (e: SdkException) {
        throw DependencyException("S3_UNAVAILABLE", "Failed to head uploaded object", e)
    }

    override fun deleteObject(bucket: String, key: String) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
        } catch (e: NoSuchKeyException) {
            // 既に存在しない場合は清掃済みとみなす
        } catch (e: SdkException) {
            throw DependencyException("S3_UNAVAILABLE", "Failed to delete object", e)
        }
    }

    override fun destroy() {
        presigner.close()
        s3.close()
    }
}
