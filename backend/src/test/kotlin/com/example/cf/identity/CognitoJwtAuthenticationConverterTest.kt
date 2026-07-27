package com.example.cf.identity

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.identity.adapter.`in`.security.CognitoJwtAuthenticationConverter
import com.example.cf.identity.application.AppUserPort
import com.example.cf.identity.application.AppUserRecord
import com.example.cf.identity.application.CognitoTokenProperties
import com.example.cf.identity.application.JitProvisioningService
import com.example.cf.identity.application.UserRolePort
import com.example.cf.shared.kernel.CurrentUser
import com.example.cf.shared.kernel.id.UlidGenerator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/**
 * Cognitoトークンの受入条件とJIT自動登録のテスト（ADR-0007）。
 */
class CognitoJwtAuthenticationConverterTest :
    FunSpec({

        val now = Instant.parse("2026-07-27T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val subject = "cognito-sub-1"
        val allowedClientId = "client-allowed"
        val newUserId = "01JZZZZZZZZZZZZZZZZZZZZZZZ"

        fun jwt(
            tokenUse: String? = "access",
            clientId: String? = allowedClientId,
            sub: String? = subject,
        ): Jwt {
            val builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", "https://cognito-idp.ap-northeast-1.amazonaws.com/pool")
            if (sub != null) builder.claim("sub", sub)
            if (tokenUse != null) builder.claim("token_use", tokenUse)
            if (clientId != null) builder.claim("client_id", clientId)
            return builder.build()
        }

        fun userRecord(status: String = "ACTIVE") = AppUserRecord(
            newUserId,
            subject,
            "$subject@cognito.invalid",
            "(未設定)",
            status,
            0L,
            now,
            now,
        )

        /** 依存をモックした変換器一式を作る。 */
        fun fixture(
            existing: AppUserRecord? = null,
            allowedClientIds: List<String> = listOf(allowedClientId),
        ): Triple<CognitoJwtAuthenticationConverter, AppUserPort, AuditRecordPort> {
            val userPort = mockk<AppUserPort>(relaxed = true)
            val rolePort = mockk<UserRolePort>(relaxed = true)
            val auditPort = mockk<AuditRecordPort>(relaxed = true)
            val idGenerator = mockk<UlidGenerator>()

            every { idGenerator.next() } returns newUserId
            every { userPort.findByCognitoSubject(subject) } returns Optional.ofNullable(existing)
            every { userPort.findById(newUserId) } returns Optional.of(userRecord())
            every { rolePort.findRoles(newUserId) } returns listOf("SUPPORTER")

            val jit = JitProvisioningService(userPort, rolePort, auditPort, clock)
            val converter = CognitoJwtAuthenticationConverter(
                rolePort,
                jit,
                CognitoTokenProperties("access", allowedClientIds),
                idGenerator,
                clock,
            )
            return Triple(converter, userPort, auditPort)
        }

        test("未登録SubjectはSUPPORTERとしてJIT登録され、監査ログへ記録される") {
            val (converter, userPort, auditPort) = fixture(existing = null)

            val token = converter.convert(jwt())

            (token.principal as CurrentUser).userId.value shouldBe newUserId
            token.authorities.map { it.authority } shouldContainExactly listOf("ROLE_SUPPORTER")

            // プロフィールはプレースホルダで作る（アクセストークンにemail/nameが無いため）
            verify {
                userPort.insert(newUserId, subject, "$subject@cognito.invalid", "(未設定)", "ACTIVE", now)
            }
            val action = slot<String>()
            verify {
                auditPort.record(newUserId, any(), "SYSTEM", null, capture(action), "User", newUserId, "SUCCESS", any())
            }
            action.captured shouldBe "USER_JIT_PROVISION"
        }

        test("登録済みSubjectでは新規作成も監査記録も行わない") {
            val (converter, userPort, auditPort) = fixture(existing = userRecord())

            converter.convert(jwt())

            verify(exactly = 0) { userPort.insert(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { auditPort.record(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

        test("IDトークン（token_use=id）は拒否する") {
            val (converter, userPort, _) = fixture(existing = null)

            shouldThrow<InvalidBearerTokenException> { converter.convert(jwt(tokenUse = "id")) }

            // 拒否されたトークンで利用者が作られないこと
            verify(exactly = 0) { userPort.insert(any(), any(), any(), any(), any(), any()) }
        }

        test("token_use クレームが無いトークンは拒否する") {
            val (converter, _, _) = fixture(existing = null)
            shouldThrow<InvalidBearerTokenException> { converter.convert(jwt(tokenUse = null)) }
        }

        test("許可していないclient_idのトークンは拒否する") {
            val (converter, userPort, _) = fixture(existing = null)

            shouldThrow<InvalidBearerTokenException> { converter.convert(jwt(clientId = "client-other")) }

            verify(exactly = 0) { userPort.insert(any(), any(), any(), any(), any(), any()) }
        }

        test("allowed-client-ids が空なら client_id を検証しない（local/testの既定）") {
            val (converter, _, _) = fixture(existing = userRecord(), allowedClientIds = emptyList())

            val token = converter.convert(jwt(clientId = "client-other"))

            (token.principal as CurrentUser).userId.value shouldBe newUserId
        }

        test("停止中の利用者は拒否する") {
            val (converter, _, _) = fixture(existing = userRecord(status = "SUSPENDED"))
            shouldThrow<InvalidBearerTokenException> { converter.convert(jwt()) }
        }

        test("subクレームが無いトークンは拒否する") {
            val (converter, _, _) = fixture(existing = null)
            shouldThrow<InvalidBearerTokenException> { converter.convert(jwt(sub = null)) }
        }
    })
