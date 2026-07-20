package com.example.cf.shared.kernel.id

import com.github.f4b6a3.ulid.UlidCreator

/**
 * 業務ID・イベントID採番Port（詳細設計 §3.3）。
 * ドメイン層はこのインターフェースにのみ依存する。
 */
interface UlidGenerator {
    /** 単調増加するULID文字列（26文字）を返す。 */
    fun next(): String
}

/** 単調増加ULID実装。同一ミリ秒内でも順序が保たれる。 */
class MonotonicUlidGenerator : UlidGenerator {
    override fun next(): String = UlidCreator.getMonotonicUlid().toString()
}
