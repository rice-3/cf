package com.example.cf.file.domain.model

import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.testsupport.Fixtures
import com.example.cf.testsupport.Fixtures.NOW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * FileObject集約の単体テスト（詳細設計 §4.7 / §6.10〜6.11）。
 */
class FileObjectTest :
    FunSpec({

        fun head(size: Long = 204_800, contentType: String = "image/jpeg") = StoredObjectHead(size, contentType)

        context("issueUpload（FL-U-001）") {
            test("有効な発行でPENDINGと失効時刻が設定される") {
                val file = Fixtures.pendingFileObject()
                file.status shouldBe FileStatus.PENDING
                file.expiresAt.shouldNotBeNull()
                file.isComplete shouldBe false
            }

            test("許可外MIMEはFILE_TYPE_NOT_ALLOWED") {
                val e = shouldThrow<ValidationException> {
                    Fixtures.pendingFileObject(contentType = "application/x-msdownload")
                }
                e.errorCode shouldBe "FILE_TYPE_NOT_ALLOWED"
            }

            test("10MB超はFILE_TOO_LARGE") {
                val e = shouldThrow<ValidationException> {
                    Fixtures.pendingFileObject(sizeBytes = FileObject.MAX_SIZE_BYTES + 1)
                }
                e.errorCode shouldBe "FILE_TOO_LARGE"
            }

            test("10MBちょうどは許可される") {
                Fixtures.pendingFileObject(sizeBytes = FileObject.MAX_SIZE_BYTES).status shouldBe FileStatus.PENDING
            }

            test("サイズ0は検証エラー") {
                shouldThrow<ValidationException> { Fixtures.pendingFileObject(sizeBytes = 0) }
            }
        }

        context("completeUpload（FL-U-002）") {
            test("実測値一致でCOMPLETEになり失効時刻が消える") {
                val file = Fixtures.pendingFileObject()
                file.completeUpload(head(), Sha256(Fixtures.SHA256_A), NOW.plusSeconds(60))
                file.status shouldBe FileStatus.COMPLETE
                file.expiresAt.shouldBeNull()
            }

            test("sha256不一致はFILE_METADATA_MISMATCH") {
                val file = Fixtures.pendingFileObject()
                val e = shouldThrow<InvalidStateException> {
                    file.completeUpload(head(), Sha256("b".repeat(64)), NOW.plusSeconds(60))
                }
                e.errorCode shouldBe "FILE_METADATA_MISMATCH"
            }

            test("オブジェクト未存在はFILE_METADATA_MISMATCH") {
                val file = Fixtures.pendingFileObject()
                val e = shouldThrow<InvalidStateException> {
                    file.completeUpload(null, Sha256(Fixtures.SHA256_A), NOW.plusSeconds(60))
                }
                e.errorCode shouldBe "FILE_METADATA_MISMATCH"
            }

            test("サイズ不一致はFILE_METADATA_MISMATCH") {
                val file = Fixtures.pendingFileObject()
                val e = shouldThrow<InvalidStateException> {
                    file.completeUpload(head(size = 1), Sha256(Fixtures.SHA256_A), NOW.plusSeconds(60))
                }
                e.errorCode shouldBe "FILE_METADATA_MISMATCH"
            }

            test("Content-Type不一致はFILE_METADATA_MISMATCH") {
                val file = Fixtures.pendingFileObject()
                val e = shouldThrow<InvalidStateException> {
                    file.completeUpload(head(contentType = "image/png"), Sha256(Fixtures.SHA256_A), NOW.plusSeconds(60))
                }
                e.errorCode shouldBe "FILE_METADATA_MISMATCH"
            }

            test("失効後はFILE_UPLOAD_EXPIRED") {
                val file = Fixtures.pendingFileObject()
                val e = shouldThrow<InvalidStateException> {
                    file.completeUpload(head(), Sha256(Fixtures.SHA256_A), NOW.plusSeconds(31 * 60))
                }
                e.errorCode shouldBe "FILE_UPLOAD_EXPIRED"
            }

            test("同一ハッシュの再実行は冪等に成功する") {
                val file = Fixtures.pendingFileObject()
                file.completeUpload(head(), Sha256(Fixtures.SHA256_A), NOW.plusSeconds(60))
                file.completeUpload(null, Sha256(Fixtures.SHA256_A), NOW.plusSeconds(120))
                file.status shouldBe FileStatus.COMPLETE
            }
        }

        context("delete（FL-U-003）") {
            test("削除でDELETEDになり完了できない") {
                val file = Fixtures.pendingFileObject()
                file.delete(NOW.plusSeconds(60))
                file.status shouldBe FileStatus.DELETED
                val e = shouldThrow<InvalidStateException> {
                    file.completeUpload(head(), Sha256(Fixtures.SHA256_A), NOW.plusSeconds(120))
                }
                e.errorCode shouldBe "FILE_INVALID_STATE"
            }
        }
    })
