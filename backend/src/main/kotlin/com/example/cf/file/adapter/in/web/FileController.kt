package com.example.cf.file.adapter.`in`.web

import com.example.cf.file.application.CompleteUploadCommand
import com.example.cf.file.application.CompleteUploadUseCase
import com.example.cf.file.application.IssueUploadCommand
import com.example.cf.file.application.IssueUploadResult
import com.example.cf.file.application.IssueUploadUrlUseCase
import com.example.cf.file.domain.model.FilePurpose
import com.example.cf.file.domain.model.Sha256
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.kernel.id.FileId
import com.example.cf.shared.kernel.id.ULID_PATTERN
import com.example.cf.shared.web.ApiEnvelope
import com.example.cf.shared.web.CorrelationIdFilter
import com.example.cf.shared.web.CurrentUserSupport
import com.example.cf.shared.web.toEnvelope
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

// ---- Request / Response DTO -------------------------------------------------

data class IssueUploadRequest(
    @field:NotBlank
    val purpose: String = "",
    @field:NotBlank
    @field:Size(min = 1, max = 255)
    val fileName: String = "",
    @field:NotBlank
    val contentType: String = "",
    @field:Min(1)
    val size: Long = 0,
    @field:NotBlank
    @field:Pattern(regexp = "[0-9a-fA-F]{64}")
    val sha256: String = "",
)

data class IssueUploadResponse(
    val fileId: String,
    val uploadUrl: String,
    val headers: Map<String, String>,
    val expiresAt: Instant,
)

private fun IssueUploadResult.toResponse() = IssueUploadResponse(
    fileId = fileId.value,
    uploadUrl = uploadUrl,
    headers = headers,
    expiresAt = expiresAt,
)

data class CompleteUploadRequest(
    @field:NotBlank
    @field:Pattern(regexp = "[0-9a-fA-F]{64}")
    val sha256: String = "",
)

data class CompleteUploadResponse(
    val fileId: String,
    val status: String,
    val downloadReference: String,
)

private fun parseFileId(raw: String): FileId = if (ULID_PATTERN.matches(raw)) {
    FileId(raw)
} else {
    throw ResourceNotFoundException("FILE_NOT_FOUND", "File $raw is not found")
}

/**
 * ファイルAPI（API-FL-001/002、詳細設計 §6.10〜6.11）。認証済み利用者のみ。
 */
@RestController
@RequestMapping("/api/v1/files")
class FileController(
    private val issueUploadUrl: IssueUploadUrlUseCase,
    private val completeUpload: CompleteUploadUseCase,
    private val userSupport: CurrentUserSupport,
    private val clock: Clock,
) {

    /** API-FL-001 署名付きアップロードURL発行。 */
    @PostMapping("/presigned-uploads")
    fun issue(
        @Valid @RequestBody body: IssueUploadRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ApiEnvelope<IssueUploadResponse>> {
        val currentUser = userSupport.requireCurrentUser()
        val purpose = runCatching { FilePurpose.valueOf(body.purpose) }.getOrElse {
            throw ValidationException(message = "purpose is invalid: ${body.purpose}")
        }
        val result = issueUploadUrl.execute(
            IssueUploadCommand(
                purpose = purpose,
                fileName = body.fileName,
                contentType = body.contentType,
                sizeBytes = body.size,
                sha256 = Sha256.of(body.sha256),
            ),
            currentUser,
            userSupport.auditContext(request),
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(result.toResponse().toEnvelope(CorrelationIdFilter.from(request), clock.instant()))
    }

    /** API-FL-002 アップロード完了。 */
    @PostMapping("/{fileId}/complete")
    fun complete(
        @PathVariable fileId: String,
        @Valid @RequestBody body: CompleteUploadRequest,
        request: HttpServletRequest,
    ): ApiEnvelope<CompleteUploadResponse> {
        val currentUser = userSupport.requireCurrentUser()
        val result = completeUpload.execute(
            CompleteUploadCommand(parseFileId(fileId), Sha256.of(body.sha256)),
            currentUser,
            userSupport.auditContext(request),
        )
        return CompleteUploadResponse(
            fileId = result.fileId.value,
            status = result.status.name,
            downloadReference = result.downloadReference,
        ).toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }
}
