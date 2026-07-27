package com.example.cf.identity.application;

import com.example.cf.audit.application.AuditRecordPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 未登録Cognito Subjectの初回自動登録（JIT provisioning、ADR-0007）。
 *
 * <p>設計書には会員登録の画面もAPIも無く、{@code app_user} 行の生成手段は
 * 本サービスだけである。付与するロールは <strong>SUPPORTER 固定</strong>とし、
 * 昇格は既存の API-AD-002（ロール更新）を通す。</p>
 *
 * <p>プロフィール（email / displayName）は<strong>プレースホルダで作る</strong>。
 * Cognitoのアクセストークンには {@code email} / {@code name} クレームが無く、
 * Resource Server はアクセストークンだけを受理するため（ADR-0007）、
 * 本人に API-US-002（プロフィール更新）で埋めてもらう前提とする。</p>
 */
@Service
public class JitProvisioningService {

    /** JITで付与する唯一のロール。ここを増やさない（増やす場合はADR-0007を改訂する）。 */
    public static final String DEFAULT_ROLE = "SUPPORTER";

    /** user_role.assigned_by へ記録する値。人手の付与と区別できるようにする。 */
    public static final String ASSIGNED_BY = "SYSTEM_JIT_PROVISIONING";

    /** プレースホルダemailのドメイン。実在しないTLDを使い、誤送信を防ぐ（RFC 6761 .invalid）。 */
    static final String PLACEHOLDER_EMAIL_DOMAIN = "@cognito.invalid";

    /** プレースホルダの表示名。本人がAPI-US-002で更新するまでの暫定値。 */
    static final String PLACEHOLDER_DISPLAY_NAME = "(未設定)";

    private final AppUserPort userRepository;
    private final UserRolePort roleRepository;
    private final AuditRecordPort auditPort;
    private final Clock clock;

    public JitProvisioningService(AppUserPort userRepository, UserRolePort roleRepository, AuditRecordPort auditPort, Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    /**
     * Cognito Subject に対応する利用者を取得し、無ければ作成する。
     *
     * <p>呼出し元（認証変換器）のトランザクションに参加する。同一Subjectの同時初回アクセスでは
     * {@code app_user.cognito_subject} の一意制約により片方が失敗するため、その場合は
     * 既存行を読み直して返す。</p>
     *
     * @param cognitoSubject Cognitoの {@code sub} クレーム
     * @param userIdSupplier 新規採番するUserIdの供給元
     * @param correlationId  相関ID（監査ログ用）
     */
    @Transactional
    public AppUserRecord findOrProvision(String cognitoSubject, Supplier<String> userIdSupplier, String correlationId) {
        return userRepository.findByCognitoSubject(cognitoSubject)
                .orElseGet(() -> provision(cognitoSubject, userIdSupplier.get(), correlationId));
    }

    private AppUserRecord provision(String cognitoSubject, String userId, String correlationId) {
        Instant now = clock.instant();
        try {
            userRepository.insert(userId, cognitoSubject, cognitoSubject + PLACEHOLDER_EMAIL_DOMAIN, PLACEHOLDER_DISPLAY_NAME, "ACTIVE",
                    now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 同一Subjectの同時初回アクセス。先に入った行を正とする。
            return userRepository.findByCognitoSubject(cognitoSubject).orElseThrow(() -> e);
        }
        roleRepository.insertRole(userId, DEFAULT_ROLE, ASSIGNED_BY, now);

        // 「誰がいつ自動登録されたか」を追えるようにする（要判断C の受入条件）。
        // 実行者は本人だが、登録操作自体はシステムが行うので source は SYSTEM とする。
        auditPort.record(userId, correlationId, "SYSTEM", null, "USER_JIT_PROVISION", "User", userId, "SUCCESS",
                Map.of("role", DEFAULT_ROLE, "assignedBy", ASSIGNED_BY));

        return userRepository.findById(userId).orElseThrow();
    }
}
