package com.example.cf.identity.application;

import com.example.cf.audit.application.AuditRecordPort;
import com.example.cf.shared.kernel.error.InvalidStateException;
import com.example.cf.shared.kernel.error.OptimisticLockConflictException;
import com.example.cf.shared.kernel.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * API-US-001/002 プロフィール取得・更新（詳細設計 §6.14）。
 *
 * <p>Identity＝Java（詳細設計 §2.2）。CurrentUser/UserId等のKotlin値は
 * Web層（Kotlin Controller）で素のStringへ変換してから本サービスへ渡す（§1.3）。</p>
 */
@Service
public class ProfileService {

    private final AppUserPort userRepository;
    private final UserRolePort roleRepository;
    private final AuditRecordPort auditPort;
    private final Clock clock;

    public ProfileService(AppUserPort userRepository, UserRolePort roleRepository, AuditRecordPort auditPort, Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    /** API-US-001。 */
    public ProfileView getProfile(String userId) {
        AppUserRecord user = requireUser(userId);
        List<String> roles = roleRepository.findRoles(userId);
        return new ProfileView(user.userId(), user.email(), user.displayName(), user.status(), roles, user.version(), user.updatedAt());
    }

    /** API-US-002。EMAIL_ALREADY_USED(409) / OPTIMISTIC_LOCK_CONFLICT(409)。 */
    @Transactional
    public ProfileUpdateResult updateProfile(String userId, String displayName, String email, long expectedVersion, String correlationId,
            String source, String clientIpHash) {
        AppUserRecord user = requireUser(userId);
        if (user.version() != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "Profile " + userId + " was updated by another user (expected=" + expectedVersion + ", actual=" + user.version() + ")");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!normalizedEmail.equalsIgnoreCase(user.email()) && userRepository.existsByEmailExcluding(normalizedEmail, userId)) {
            throw new InvalidStateException("EMAIL_ALREADY_USED", "Email is already used by another account");
        }

        Instant now = clock.instant();
        int updated = userRepository.updateProfile(userId, displayName, normalizedEmail, expectedVersion, now);
        if (updated == 0) {
            // 事前チェック後の競合（レースコンディション）。最新状態の再取得を促す。
            throw new OptimisticLockConflictException("Profile " + userId + " was updated by another user");
        }

        auditPort.record(userId, correlationId, source, clientIpHash, "PROFILE_UPDATE", "User", userId, "SUCCESS");
        return new ProfileUpdateResult(userId, displayName, normalizedEmail, expectedVersion + 1);
    }

    private AppUserRecord requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User " + userId + " is not found"));
    }

    public record ProfileView(String userId, String email, String displayName, String status, List<String> roles, long version,
            Instant updatedAt) {
    }

    public record ProfileUpdateResult(String userId, String displayName, String email, long version) {
    }
}
