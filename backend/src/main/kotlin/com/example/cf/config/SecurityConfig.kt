package com.example.cf.config

import com.example.cf.shared.kernel.CurrentUser
import com.example.cf.shared.kernel.RoleCode
import com.example.cf.shared.kernel.id.ULID_PATTERN
import com.example.cf.shared.kernel.id.UserId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock

/**
 * 開発用ヘッダー認証（local/testプロファイル限定）。
 *
 * Cognito OIDC（基本設計 §10.1）の代替として、教育用ローカル環境では
 * `X-Dev-User`（内部UserId・ULID）と `X-Dev-Roles`（カンマ区切りロール）で認証する。
 * dev以上の環境ではJWT Resource Server構成（[ResourceServerSecurityConfig]）を使用し、
 * 本フィルタは絶対に有効化しない。
 */
class DevHeaderAuthenticationFilter(private val clock: Clock) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val userIdHeader = request.getHeader("X-Dev-User")
        if (userIdHeader != null && ULID_PATTERN.matches(userIdHeader)) {
            val roles = request.getHeader("X-Dev-Roles")
                ?.split(",")
                ?.mapNotNull { runCatching { RoleCode.valueOf(it.trim()) }.getOrNull() }
                ?.toSet()
                ?: emptySet()
            val currentUser = CurrentUser(
                userId = UserId(userIdHeader),
                roles = roles,
                authenticatedAt = clock.instant(),
            )
            val authorities = roles.map { SimpleGrantedAuthority("ROLE_${it.name}") }
            val authentication = UsernamePasswordAuthenticationToken(currentUser, null, authorities)
            SecurityContextHolder.getContext().authentication = authentication
        }
        filterChain.doFilter(request, response)
    }
}

/**
 * 共通の認可ルール（詳細設計 §11.2）。
 * 公開APIは公開プロジェクトGETとhealthエンドポイントに限定する。
 * 詳細な所有権・状態の認可はUseCase側で必ず検証する（基本設計 §2.3）。
 */
private fun HttpSecurity.applyCommonRules(): HttpSecurity = this
    .csrf { it.disable() } // BFF側でCSRF対策（基本設計 §10.2）。API本体はトークン認証前提。
    .sessionManagement { it.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS) }
    .authorizeHttpRequests { auth ->
        auth
            .requestMatchers(HttpMethod.GET, "/api/v1/projects", "/api/v1/projects/*").permitAll()
            .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
            // メトリクス収集エンドポイント（詳細設計 §12.5/§9.3）。
            // 本番ではALBがこのパスを外部公開しない前提（VPC内のCollector/CloudWatch Agentのみ到達）。
            // 公開したくない場合は management.endpoints.web.exposure.include から prometheus を除く。
            .requestMatchers("/actuator/prometheus", "/actuator/info").permitAll()
            // OpenAPI仕様（springdoc）。本番で公開したくない場合は springdoc.api-docs.enabled=false で無効化する。
            .requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
            // Swagger UI（springdoc）。本番で隠す場合は springdoc.swagger-ui.enabled=false（下記application.yml）。
            .requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll()
            // エラー応答の内部ディスパッチ。認証必須にするとProblem Detailsの本文が失われる（§6.3）
            .requestMatchers("/error").permitAll()
            // Webhookの認証はProvider署名検証で行う（詳細設計 §6.8）。署名不正は401。
            .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhooks").permitAll()
            .requestMatchers("/api/v1/owner/**").hasRole("OWNER")
            .requestMatchers("/api/v1/projects/*/supports").hasRole("SUPPORTER")
            .requestMatchers("/api/v1/me/supports/**", "/api/v1/me/supports").hasRole("SUPPORTER")
            .requestMatchers("/api/v1/reviews/**").hasRole("REVIEWER")
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            // API-AU-001/002（工程9）。/admin/**の配下ではないため個別に指定する（基本設計 §6.6）。
            .requestMatchers("/api/v1/audit-logs", "/api/v1/ai-activities").hasAnyRole("ADMIN", "AUDITOR")
            .requestMatchers("/api/v1/operations/**").hasAnyRole("OPERATOR", "ADMIN")
            .anyRequest().authenticated()
    }

@Configuration
@EnableWebSecurity
@Profile("local", "test")
class DevSecurityConfig {

    @Bean
    fun devSecurityFilterChain(http: HttpSecurity, clock: Clock): SecurityFilterChain = http.applyCommonRules()
        .addFilterBefore(DevHeaderAuthenticationFilter(clock), UsernamePasswordAuthenticationFilter::class.java)
        .build()
}

/**
 * dev以上の環境向け: Cognito発行JWTを検証するResource Server構成（基本設計 §10.1）。
 * `spring.security.oauth2.resourceserver.jwt.issuer-uri`（環境変数 `COGNITO_ISSUER`）の設定が必須。
 * 未設定のままdev以上へ起動するとJwtDecoder Bean不足で起動失敗する（意図的なフェイルファスト）。
 * Cognito Subject → 内部UserId変換とロール解決は [CognitoJwtAuthenticationConverter] が行う（工程9）。
 */
@Configuration
@EnableWebSecurity
@Profile("!local & !test")
class ResourceServerSecurityConfig(
    private val cognitoJwtAuthenticationConverter: com.example.cf.identity.adapter.`in`.security.CognitoJwtAuthenticationConverter,
) {

    @Bean
    fun resourceServerFilterChain(http: HttpSecurity): SecurityFilterChain = http.applyCommonRules()
        .oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(cognitoJwtAuthenticationConverter) } }
        .build()
}
