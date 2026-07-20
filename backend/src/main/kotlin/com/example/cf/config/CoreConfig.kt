package com.example.cf.config

import com.example.cf.funding.domain.service.FundingEligibilityPolicy
import com.example.cf.project.domain.service.ProjectSubmissionPolicy
import com.example.cf.shared.kernel.id.MonotonicUlidGenerator
import com.example.cf.shared.kernel.id.UlidGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 共通Bean定義。
 * `Instant.now()` の直接呼出しは禁止し、必ず [Clock] を注入する（詳細設計 §1.3）。
 */
@Configuration
class CoreConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun ulidGenerator(): UlidGenerator = MonotonicUlidGenerator()

    @Bean
    fun projectSubmissionPolicy(): ProjectSubmissionPolicy = ProjectSubmissionPolicy()

    @Bean
    fun fundingEligibilityPolicy(): FundingEligibilityPolicy = FundingEligibilityPolicy()
}
