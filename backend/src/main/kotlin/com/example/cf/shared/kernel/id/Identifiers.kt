package com.example.cf.shared.kernel.id

/**
 * ULID形式（Crockford Base32・26文字）。詳細設計 §3.1。
 */
val ULID_PATTERN = Regex("[0-9A-HJKMNP-TV-Z]{26}")

private fun requireUlid(value: String, name: String) {
    require(ULID_PATTERN.matches(value)) { "$name must be a 26-character ULID: $value" }
}

@JvmInline
value class UserId(val value: String) {
    init { requireUlid(value, "UserId") }
    companion object {
        fun newId(generator: UlidGenerator) = UserId(generator.next())
    }
}

@JvmInline
value class ProjectId(val value: String) {
    init { requireUlid(value, "ProjectId") }
    companion object {
        fun newId(generator: UlidGenerator) = ProjectId(generator.next())
    }
}

@JvmInline
value class RewardPlanId(val value: String) {
    init { requireUlid(value, "RewardPlanId") }
    companion object {
        fun newId(generator: UlidGenerator) = RewardPlanId(generator.next())
    }
}

@JvmInline
value class ReviewId(val value: String) {
    init { requireUlid(value, "ReviewId") }
    companion object {
        fun newId(generator: UlidGenerator) = ReviewId(generator.next())
    }
}

@JvmInline
value class FileId(val value: String) {
    init { requireUlid(value, "FileId") }
    companion object {
        fun newId(generator: UlidGenerator) = FileId(generator.next())
    }
}

@JvmInline
value class SupportId(val value: String) {
    init { requireUlid(value, "SupportId") }
    companion object {
        fun newId(generator: UlidGenerator) = SupportId(generator.next())
    }
}

@JvmInline
value class SupportItemId(val value: String) {
    init { requireUlid(value, "SupportItemId") }
    companion object {
        fun newId(generator: UlidGenerator) = SupportItemId(generator.next())
    }
}

@JvmInline
value class PaymentId(val value: String) {
    init { requireUlid(value, "PaymentId") }
    companion object {
        fun newId(generator: UlidGenerator) = PaymentId(generator.next())
    }
}

@JvmInline
value class RefundId(val value: String) {
    init { requireUlid(value, "RefundId") }
    companion object {
        fun newId(generator: UlidGenerator) = RefundId(generator.next())
    }
}

@JvmInline
value class NotificationId(val value: String) {
    init { requireUlid(value, "NotificationId") }
    companion object {
        fun newId(generator: UlidGenerator) = NotificationId(generator.next())
    }
}

@JvmInline
value class EventId(val value: String) {
    init { requireUlid(value, "EventId") }
    companion object {
        fun newId(generator: UlidGenerator) = EventId(generator.next())
    }
}

@JvmInline
value class AuditId(val value: String) {
    init { requireUlid(value, "AuditId") }
    companion object {
        fun newId(generator: UlidGenerator) = AuditId(generator.next())
    }
}

@JvmInline
value class HistoryId(val value: String) {
    init { requireUlid(value, "HistoryId") }
    companion object {
        fun newId(generator: UlidGenerator) = HistoryId(generator.next())
    }
}

/**
 * ログ・API追跡用の相関ID。ULIDに限定せず64文字までの識別子を許容する（§11.2）。
 */
@JvmInline
value class CorrelationId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 64) {
            "CorrelationId must be 1..64 characters"
        }
    }
}
