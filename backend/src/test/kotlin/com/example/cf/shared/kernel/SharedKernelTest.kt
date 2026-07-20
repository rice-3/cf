package com.example.cf.shared.kernel

import com.example.cf.shared.kernel.id.MonotonicUlidGenerator
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.money.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.shouldBe

class SharedKernelTest : FunSpec({

    context("Money") {
        test("負値は生成できない") {
            shouldThrow<IllegalArgumentException> { Money.of(-1) }
        }

        test("加算・乗算・比較") {
            (Money.of(1_000) + Money.of(500)) shouldBe Money.of(1_500)
            (Money.of(3_000) * 3) shouldBe Money.of(9_000)
            (Money.of(500_000) >= Money.of(499_999)) shouldBe true
        }

        test("減算で負になる場合は拒否") {
            shouldThrow<IllegalArgumentException> { Money.of(100) - Money.of(200) }
        }
    }

    context("ID") {
        test("ULID形式以外は拒否") {
            shouldThrow<IllegalArgumentException> { ProjectId("not-a-ulid") }
            shouldThrow<IllegalArgumentException> { ProjectId("") }
        }

        test("MonotonicUlidGeneratorは単調増加のULIDを生成する") {
            val generator = MonotonicUlidGenerator()
            val ids = (1..100).map { generator.next() }
            ids.forEach { ProjectId(it) } // 形式検証
            ids.shouldBeSorted()
            ids.toSet().size shouldBe 100
        }
    }

    context("Version / IdempotencyKey") {
        test("Versionは0以上") {
            shouldThrow<IllegalArgumentException> { Version(-1) }
            Version(0).increment() shouldBe Version(1)
        }

        test("IdempotencyKeyは許可文字のみ") {
            IdempotencyKey("abc-123_XYZ").value shouldBe "abc-123_XYZ"
            shouldThrow<IllegalArgumentException> { IdempotencyKey("日本語キー") }
            shouldThrow<IllegalArgumentException> { IdempotencyKey("a".repeat(101)) }
        }
    }
})
