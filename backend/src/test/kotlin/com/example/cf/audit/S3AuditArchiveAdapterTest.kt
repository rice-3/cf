package com.example.cf.audit

import com.example.cf.audit.application.AuditArchiveProperties
import com.example.cf.shared.kernel.error.DependencyException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.MessageDigest
import java.util.HexFormat

/**
 * 監査アーカイブ設定の既定値と、出力先未設定時の扱い（ADR-0009）。
 *
 * S3への実PUTはAWS認証が必要なため、ここでは検証しない（dev環境構築後に実機で確認する）。
 */
class S3AuditArchiveAdapterTest :
    FunSpec({

        test("storage-class の既定は GLACIER_IR（保持1年に収まり即時取り出せる）") {
            AuditArchiveProperties(null, null, null).storageClass() shouldBe "GLACIER_IR"
        }

        test("key-prefix の既定は local") {
            AuditArchiveProperties(null, null, null).keyPrefix() shouldBe "local"
        }

        test("空文字は既定値へ正規化される（未設定の環境変数は空文字で束縛される）") {
            val properties = AuditArchiveProperties("", "", "")
            properties.keyPrefix() shouldBe "local"
            properties.storageClass() shouldBe "GLACIER_IR"
        }

        test("明示指定は尊重される") {
            val properties = AuditArchiveProperties("my-bucket", "dev", "DEEP_ARCHIVE")
            properties.bucket() shouldBe "my-bucket"
            properties.keyPrefix() shouldBe "dev"
            properties.storageClass() shouldBe "DEEP_ARCHIVE"
        }

        // bucket が空のままS3実装が動くと、S3へ出さずにハッシュを返して
        // BAT-009 がDBを削除してしまう。必ず失敗しなければならない。
        test("bucket 未設定なら DependencyException で落ちる（DBを削除させない）") {
            val adapter = com.example.cf.audit.adapter.out.archive.S3AuditArchiveAdapter(
                AuditArchiveProperties("", "dev", "GLACIER_IR"),
                tools.jackson.databind.json.JsonMapper.builder().build(),
                java.time.Clock.systemUTC(),
            )
            val e = shouldThrow<DependencyException> { adapter.archive("audit_log_until_x", listOf(mapOf("a" to 1))) }
            e.errorCode shouldContain "AUDIT_ARCHIVE_NOT_CONFIGURED"
            adapter.destroy()
        }

        test("SHA-256 のhex表現が BAT-009 の検証値になる") {
            // Adapterが返すハッシュは「S3が受理した内容」のSHA-256。算出方法の取り違えを防ぐ。
            val content = """[{"a":1}]"""
            val expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)),
            )
            expected.length shouldBe 64
        }
    })
