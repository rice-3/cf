package com.example.cf.identity.adapter.in.security;

import com.example.cf.identity.application.AppUserRecord;
import com.example.cf.identity.adapter.out.persistence.AppUserRepository;
import com.example.cf.identity.adapter.out.persistence.UserRoleRepository;
import com.example.cf.shared.kernel.CurrentUser;
import com.example.cf.shared.kernel.id.UlidGenerator;
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

/**
 * Cognito発行JWT → CurrentUser 変換器（基本設計 §9.1/§10.1）。
 *
 * <p>Cognito Subject（{@code sub}クレーム）を内部UserIdへ変換し、
 * ロールはトークンではなくアプリケーションDB（user_role）を正として解決する
 * （基本設計 §9.1「ロールはアプリケーションDBを正とし、必要な範囲だけトークンへ反映する」）。</p>
 *
 * <p>TODO(question): 未登録のCognito Subjectを初回アクセス時にJIT（Just-In-Time）で
 * app_userへ自動登録し、既定ロールSUPPORTERを付与する実装としている。
 * これは標準的なOIDC統合パターンだが、「誰でもSignUpすれば即座に支援者として登録される」
 * ことを許容するかは業務判断が必要なため、dev環境投入前に承認者へ確認すること。
 * 許容しない場合は、事前登録（管理者によるInvite）方式へ変更する。</p>
 */
@Component
public class CognitoJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AppUserRepository userRepository;
    private final UserRoleRepository roleRepository;
    private final UlidGenerator idGenerator;
    private final Clock clock;

    public CognitoJwtAuthenticationConverter(AppUserRepository userRepository, UserRoleRepository roleRepository, UlidGenerator idGenerator,
            Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw invalidToken("Cognito token does not contain a subject claim");
        }

        AppUserRecord user = userRepository.findByCognitoSubject(subject).orElseGet(() -> provisionUser(subject, jwt));

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

    /** 初回ログイン時の自動登録（JIT provisioning）。既定ロールはSUPPORTER。 */
    private AppUserRecord provisionUser(String subject, Jwt jwt) {
        String userId = idGenerator.next();
        String email = jwt.getClaimAsString("email");
        String displayName = jwt.getClaimAsString("name");
        Instant now = clock.instant();
        userRepository.insert(userId, subject, email != null ? email : subject + "@cognito.invalid",
                displayName != null ? displayName : "New User", "ACTIVE", now);
        roleRepository.insertRole(userId, "SUPPORTER", "SYSTEM_JIT_PROVISIONING", now);
        return userRepository.findById(userId).orElseThrow();
    }

    private InvalidBearerTokenException invalidToken(String message) {
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, message, null);
        return new InvalidBearerTokenException(error.getDescription());
    }
}
