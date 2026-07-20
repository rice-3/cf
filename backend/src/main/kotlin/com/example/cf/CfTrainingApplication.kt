package com.example.cf

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * CF-Training Backend（クラウドファンディング型教育・実践開発システム）。
 * モジュラーモノリス構成。コンテキスト境界は com.example.cf.* パッケージで表現する（ADR-0001）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // BAT-006 Outbox配送等のバッチ起動（基本設計 §8.1）
class CfTrainingApplication

fun main(args: Array<String>) {
    runApplication<CfTrainingApplication>(*args)
}
