package com.example.cf.shared.idempotency

import com.example.cf.shared.kernel.IdempotencyKey

/** 冪等キー確認の結果（詳細設計 §5.3 冪等性）。 */
sealed interface IdempotencyOutcome {

    /** 新規実行。処理成功後に [IdempotencyPort.complete] を呼ぶこと。 */
    data object Proceed : IdempotencyOutcome

    /** 完了済み。保存済み応答をそのまま返す（§3.6: 初回結果を返す）。 */
    data class Replay(
        val responseStatus: Int,
        val responseBody: Map<String, Any?>,
    ) : IdempotencyOutcome
}

/**
 * 冪等記録Port（idempotency_record、詳細設計 §8.21）。
 * scope＋actorId＋keyで一意。処理中は409 IDEMPOTENCY_IN_PROGRESS、
 * 同一キーでリクエスト内容が異なる場合は409 IDEMPOTENCY_KEY_CONFLICT。
 */
interface IdempotencyPort {

    /**
     * 冪等実行を開始する。業務トランザクションに参加してPROCESSING記録を挿入する。
     * 業務処理が例外で失敗した場合はロールバックで記録も消え、再試行可能になる。
     */
    fun begin(scope: String, actorId: String, key: IdempotencyKey, requestHash: String): IdempotencyOutcome

    /** 応答を保存してCOMPLETEDにする。beginと同一トランザクションで呼ぶ。 */
    fun complete(
        scope: String,
        actorId: String,
        key: IdempotencyKey,
        responseStatus: Int,
        responseBody: Map<String, Any?>,
    )
}
