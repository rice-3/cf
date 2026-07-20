package com.example.cf.identity.adapter.`in`.security

import com.example.cf.shared.kernel.CurrentUser
import com.example.cf.shared.kernel.RoleCode
import com.example.cf.shared.kernel.id.UserId
import java.time.Instant

/**
 * Java（Cognito JWT変換器）からCurrentUserを組み立てるための明示的変換（§1.3）。
 * `UserId`はKotlinの値クラスのため、Java側からの直接構築は避けこの関数を経由する。
 */
fun currentUserOf(userId: String, roleNames: Collection<String>, authenticatedAt: Instant): CurrentUser {
    val roles = roleNames.mapNotNull { runCatching { RoleCode.valueOf(it) }.getOrNull() }.toSet()
    return CurrentUser(UserId(userId), roles, authenticatedAt)
}
