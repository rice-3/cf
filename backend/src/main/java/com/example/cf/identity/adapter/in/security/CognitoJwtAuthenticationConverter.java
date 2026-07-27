package com.example.cf.identity.adapter.in.security;

import com.example.cf.identity.application.AppUserRecord;
import com.example.cf.identity.application.CognitoTokenProperties;
import com.example.cf.identity.application.JitProvisioningService;
import com.example.cf.identity.application.UserRolePort;
import com.example.cf.shared.kernel.CurrentUser;
import com.example.cf.shared.kernel.id.UlidGenerator;
import com.example.cf.shared.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cognito発行JWT → CurrentUser 変換器（基本設計 §9.1/§10.1、ADR-0007）。
 *
 * <p>Cognito Subject（{@code sub}クレーム）を内部UserIdへ変換し、
 * ロールはトークンではなくアプリケーションDB（user_role）を正として解決する
 * （基本設計 §9.1「ロールはアプリケーションDBを正とし、必要な範囲だけトークンへ反映する」）。</p>
 *
 * <p>未登録Subjectは初回アクセス時に SUPPORTER としてJIT自動登録する（ADR-0007 で承認済み）。
 * 登録処理そのものは {@link JitProvisioningService} が持ち、本クラスはトークン検証と変換に徹する。</p>
 *
 * <h2>トークン受入条件の検証をここで行う理由</h2>
 *
 * <p>署名と issuer は {@code JwtDecoder} が検証済みだが、Cognitoは ID トークンとアクセストークンを
 * <strong>同じ issuer・同じ JWKS</strong> で発行するため、それだけでは両者を区別できない。
 * 用途の異なるトークンを受理しないよう {@code token_use} と {@code client_id} を検証する。
 * {@code JwtDecoder} のバリデータへ寄せる案もあるが、Decoder Bean を自前定義すると
 * 起動時のOIDCメタデータ取得まで抱えることになるため、既に不正トークンを弾いている
 * 本クラスへ集約する。いずれの経路でも応答は 401 {@code invalid_token} で同じ。</p>
 */
@Component
public class CognitoJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRolePort roleRepository;
    private final JitProvisioningService jitProvisioningService;
    private final CognitoTokenProperties tokenProperties;
    private final UlidGenerator idGenerator;
    private final Clock clock;

    public CognitoJwtAuthenticationConverter(UserRolePort roleRepository, JitProvisioningService jitProvisioningService,
            CognitoTokenProperties tokenProperties, UlidGenerator idGenerator, Clock clock) {
        this.roleRepository = roleRepository;
        this.jitProvisioningService = jitProvisioningService;
        this.tokenProperties = tokenProperties;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AbstractAuthenticationToken convert(Jwt jwt) {
        verifyTokenUse(jwt);
        verifyClientId(jwt);

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw invalidToken("Cognito token does not contain a subject claim");
        }

        AppUserRecord user = jitProvisioningService.findOrProvision(subject, idGenerator::next, correlationId());

        if (!"ACTIVE".equals(user.status())) {
            throw invalidToken("User account is not active: " + user.status());
        }

        List<String> roleNames = roleRepository.findRoles(user.userId());
        Instant now = clock.instant();
        CurrentUser currentUser = CurrentUserFactoryKt.currentUserOf(user.userId(), roleNames, now);

        List<GrantedAuthority> authorities = roleNames.stream().map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new UsernamePasswordAuthenticationToken(currentUser, jwt, authorities);
    }

    /**
     * {@code token_use} の検証。IDトークンをBearerとして持ち込む経路を塞ぐ。
     * IDトークンは利用者本人向けの身元情報であり、API呼出しの認可根拠にしない。
     */
    private void verifyTokenUse(Jwt jwt) {
        String tokenUse = jwt.getClaimAsString("token_use");
        if (!tokenProperties.expectedTokenUse().equals(tokenUse)) {
            // 期待値のみを出し、受け取った値はメッセージへ含めない（応答から内部条件を推測させない）
            throw invalidToken("Token is not a Cognito " + tokenProperties.expectedTokenUse() + " token");
        }
    }

    /**
     * {@code client_id} の検証。同一User Poolに別のアプリクライアントを追加しても、
     * 許可したクライアントが発行したトークンだけを受理する。
     * 未設定（local/test）では検証しない。
     */
    private void verifyClientId(Jwt jwt) {
        if (!tokenProperties.verifiesClientId()) {
            return;
        }
        String clientId = jwt.getClaimAsString("client_id");
        if (clientId == null || !tokenProperties.allowedClientIds().contains(clientId)) {
            throw invalidToken("Token was not issued to an allowed client");
        }
    }

    /**
     * 監査ログ用の相関ID。CorrelationIdFilter が MDC へ入れた値を使う
     * （同フィルタは {@code HIGHEST_PRECEDENCE} でSpring Securityより先に走る）。
     * 取得できない場合でも監査記録を落とさないよう、その場で採番する。
     */
    private String correlationId() {
        String fromMdc = MDC.get(CorrelationIdFilter.MDC_KEY);
        return fromMdc != null && !fromMdc.isBlank() ? fromMdc : "cor_" + UUID.randomUUID();
    }

    private InvalidBearerTokenException invalidToken(String message) {
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, message, null);
        return new InvalidBearerTokenException(error.getDescription());
    }
}
