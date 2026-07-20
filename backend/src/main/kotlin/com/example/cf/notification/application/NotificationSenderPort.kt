package com.example.cf.notification.application

import com.example.cf.notification.domain.model.NotificationChannel

/** 送信結果。失敗時、再試行しても解決しない業務エラーは permanent=true とする（基本設計 §8.2）。 */
sealed interface SendResult {
    data class Sent(val providerMessageId: String?) : SendResult

    data class Failed(val errorCode: String, val permanent: Boolean) : SendResult
}

/**
 * 通知送信Port（詳細設計 §10.3 Amazon SES）。
 * local/testはMock、dev以上はSESを使用する。
 * 実装は宛先を平文でログ出力してはならない（ログは宛先ハッシュ）。
 */
interface NotificationSenderPort {

    fun send(
        channel: NotificationChannel,
        templateId: String,
        recipientAddress: String?,
        variables: Map<String, Any?>,
    ): SendResult
}
