package com.example.cf

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * CF-Training Backend（クラウドファンディング型教育・実践開発システム）。
 * モジュラーモノリス構成。コンテキスト境界は com.example.cf.* パッケージで表現する（ADR-0001）。
 *
 * スケジューリング（@EnableScheduling）とShedLockは [com.example.cf.config.SchedulingConfig] で
 * 有効化する（testプロファイルでは無効。基本設計 §8.3、ADR-0003）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class CfTrainingApplication

fun main(args: Array<String>) {
    runApplication<CfTrainingApplication>(*args)
}
