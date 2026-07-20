package com.example.cf.audit.application

import com.example.cf.shared.kernel.AuditContext

/**
 * Kotlin側からAuditContextで監査記録するための拡張（Java/Kotlin境界の明示的変換、§1.3）。
 */
fun AuditRecordPort.record(
    context: AuditContext,
    action: String,
    resourceType: String,
    resourceId: String?,
    result: String,
) {
    record(
        context.actorUserId?.value,
        context.correlationId.value,
        context.source.name,
        context.clientIpHash,
        action,
        resourceType,
        resourceId,
        result,
    )
}
