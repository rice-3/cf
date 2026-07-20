package com.example.cf.file.adapter.out.storage

import com.example.cf.file.application.FileStoragePort
import com.example.cf.file.application.PresignedUpload
import com.example.cf.file.domain.model.StoredObjectHead
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * S3の教育用スタブ（ADR-006: 教育環境はMock）。local/testプロファイル限定。
 *
 * 発行時に申告されたメタデータを記録し、HeadObjectは「申告どおりアップロード成功した」
 * ものとして返す。実PUTは行われないため、実S3・実署名の検証はdev以上の環境
 * （[S3FileStorageAdapter]）で行うこと。
 */
@Component
@Profile("local", "test")
class StubFileStorageAdapter : FileStoragePort {

    private val objects = ConcurrentHashMap<String, StoredObjectHead>()

    override fun presignPutObject(
        bucket: String,
        key: String,
        contentType: String,
        sizeBytes: Long,
        expiry: Duration,
    ): PresignedUpload {
        objects["$bucket/$key"] = StoredObjectHead(sizeBytes, contentType)
        return PresignedUpload(
            url = "https://s3.stub.invalid/$bucket/$key?X-Amz-Expires=${expiry.seconds}&X-Amz-Signature=stub",
            headers = mapOf("Content-Type" to contentType),
        )
    }

    override fun headObject(bucket: String, key: String): StoredObjectHead? = objects["$bucket/$key"]

    override fun deleteObject(bucket: String, key: String) {
        objects.remove("$bucket/$key")
    }
}
