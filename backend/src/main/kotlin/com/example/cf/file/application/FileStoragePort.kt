package com.example.cf.file.application

import com.example.cf.file.domain.model.StoredObjectHead
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** 署名付きアップロードURL（§10.2）。headersはPUT時に必須のヘッダー。 */
data class PresignedUpload(
    val url: String,
    val headers: Map<String, String>,
)

/**
 * S3操作Port（詳細設計 §10.2）。実装は adapter.out.storage。
 * local/testは教育用スタブ、dev以上はAWS SDKによる実S3を使用する（ADR-006）。
 */
interface FileStoragePort {

    /** PutObject用の署名付きURLを発行する。Content-Typeは署名条件に含める（§10.2）。 */
    fun presignPutObject(
        bucket: String,
        key: String,
        contentType: String,
        sizeBytes: Long,
        expiry: Duration,
    ): PresignedUpload

    /** HeadObjectでアップロード済みオブジェクトの実測値を取得する。未存在はnull。 */
    fun headObject(bucket: String, key: String): StoredObjectHead?

    /** オブジェクトを削除する（BAT-008 ファイル清掃）。未存在でも成功として扱う。 */
    fun deleteObject(bucket: String, key: String)
}

/**
 * ファイル保管設定（§13.1 環境変数）。dev以上はECS環境変数で上書きする。
 */
@ConfigurationProperties(prefix = "cf.file")
data class FileStorageProperties(
    val bucket: String = "cf-local-files",
    /** S3 Keyの先頭に付く環境識別子（§10.2: env/userId/fileId/randomized-name）。 */
    val keyPrefix: String = "local",
    /** URL期限は5分（§10.2）。 */
    val uploadUrlExpirySeconds: Long = 300,
    /** 未完了アップロードの失効までの分数（BAT-008清掃対象、§8.15 expires_at）。 */
    val pendingExpiryMinutes: Long = 30,
)
