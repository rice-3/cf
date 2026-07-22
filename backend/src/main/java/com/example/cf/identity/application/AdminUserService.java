package com.example.cf.identity.application;

import com.example.cf.audit.application.AuditRecordPort;
import com.example.cf.shared.kernel.PageResult;
import com.example.cf.shared.kernel.error.AccessDeniedException;
import com.example.cf.shared.kernel.error.InvalidStateException;
import com.example.cf.shared.kernel.error.OptimisticLockConflictException;
import com.example.cf.shared.kernel.error.ResourceNotFoundException;
import com.example.cf.shared.kernel.error.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * API-AD-001〜003 会員検索・ロール更新・会員停止（基本設計 §6.6、詳細設計 §6.12）。
 */
@Service
public class AdminUserService {

    private final AppUserPort userRepository;
    private final UserRolePort roleRepository;
    private final AuditRecordPort auditPort;
    private final Clock clock;

    public AdminUserService(AppUserPort userRepository, UserRolePort roleRepository, AuditRecordPort auditPort, Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    /** API-AD-001。 */
    public PageResult<AdminUserListItem> search(String keyword, String status, int page, int size) {
        AppUserPort.SearchResult result = userRepository.search(keyword, status, page, size);
        List<AdminUserListItem> items = result.items().stream().map(u -> new AdminUserListItem(u.userId(), u.email(), u.displayName(),
                u.status(), roleRepository.findRoles(u.userId()), u.version(), u.createdAt(), u.updatedAt())).toList();
        int totalPages = size == 0 ? 0 : (int) ((result.totalElements() + size - 1) / size);
        return new PageResult<>(items, page, size, result.totalElements(), totalPages);
    }

    /**
     * API-AD-002。403 ROLE_UPDATE_FORBIDDEN（自己のADMIN剥奪、詳細設計UC-AD-001「自己権限剥奪検証」）、
     * 409 OPTIMISTIC_LOCK_CONFLICT。
     */
    @Transactional
    public AdminUserRoleUpdateResult updateRoles(String targetUserId, List<String> roles, long expectedVersion, String reason,
            String actingUserId, String correlationId, String source, String clientIpHash) {
        validateRoles(roles);
        if (actingUserId.equals(targetUserId) && !roles.contains("ADMIN")) {
            throw new AccessDeniedException("ROLE_UPDATE_FORBIDDEN", "Admin cannot remove own ADMIN role");
        }

        requireUser(targetUserId);
        Instant now = clock.instant();
        int updated = userRepository.touchVersion(targetUserId, expectedVersion, now);
        if (updated == 0) {
            throw new OptimisticLockConflictException("User " + targetUserId + " was updated by another user");
        }

        List<String> distinctRoles = roles.stream().distinct().toList();
        roleRepository.replaceRoles(targetUserId, distinctRoles, actingUserId, now);

        auditPort.record(actingUserId, correlationId, source, clientIpHash, "USER_ROLE_UPDATE", "User", targetUserId, "SUCCESS",
                Map.of("roles", distinctRoles, "reason", reason));
        return new AdminUserRoleUpdateResult(targetUserId, distinctRoles, expectedVersion + 1);
    }

    /** API-AD-003。403 USER_SUSPEND_FORBIDDEN（自己停止禁止）、409 USER_INVALID_STATE（停止済み）。 */
    @Transactional
    public AdminUserSuspendResult suspend(String targetUserId, long expectedVersion, String reason, String actingUserId,
            String correlationId, String source, String clientIpHash) {
        if (actingUserId.equals(targetUserId)) {
            throw new AccessDeniedException("USER_SUSPEND_FORBIDDEN", "Cannot suspend own account");
        }

        AppUserRecord target = requireUser(targetUserId);
        if ("SUSPENDED".equals(target.status())) {
            throw new InvalidStateException("USER_INVALID_STATE", "User " + targetUserId + " is already suspended");
        }

        Instant now = clock.instant();
        int updated = userRepository.updateStatus(targetUserId, "SUSPENDED", expectedVersion, now);
        if (updated == 0) {
            throw new OptimisticLockConflictException("User " + targetUserId + " was updated by another user");
        }

        auditPort.record(actingUserId, correlationId, source, clientIpHash, "USER_SUSPEND", "User", targetUserId, "SUCCESS",
                reason == null || reason.isBlank() ? Collections.emptyMap() : Map.of("reason", reason));
        return new AdminUserSuspendResult(targetUserId, "SUSPENDED", expectedVersion + 1);
    }

    private void validateRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new ValidationException("VALIDATION_ERROR", "roles must contain at least one role", Collections.emptyList());
        }
        List<String> assignable = roleRepository.findAssignableRoleCodes();
        for (String role : roles) {
            if (!assignable.contains(role)) {
                throw new ValidationException("VALIDATION_ERROR", "role is not assignable: " + role, Collections.emptyList());
            }
        }
    }

    private AppUserRecord requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User " + userId + " is not found"));
    }

    public record AdminUserListItem(String userId, String email, String displayName, String status, List<String> roles, long version,
            Instant createdAt, Instant updatedAt) {
    }

    public record AdminUserRoleUpdateResult(String userId, List<String> roles, long version) {
    }

    public record AdminUserSuspendResult(String userId, String status, long version) {
    }
}
