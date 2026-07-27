package com.example.cf.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Cognito発行トークンの受入条件（基本設計 §9.1、ADR-0007）。
 *
 * <p>Cognitoは ID トークンとアクセストークンを<strong>同じ issuer・同じ JWKS</strong> で発行するため、
 * 署名と issuer の検証だけでは両者を区別できない。用途の異なるトークンを取り違えて受理しないよう、
 * {@code token_use} と {@code client_id} を明示的に検証する。</p>
 *
 * @param expectedTokenUse 受理する {@code token_use}。Resource Server はアクセストークンを受ける
 * @param allowedClientIds 受理する {@code client_id}。空なら検証しない（local/test 用の既定）
 */
@ConfigurationProperties(prefix = "cf.identity.cognito")
public record CognitoTokenProperties(String expectedTokenUse, List<String> allowedClientIds) {

    public CognitoTokenProperties {
        if (expectedTokenUse == null || expectedTokenUse.isBlank()) {
            expectedTokenUse = "access";
        }
        allowedClientIds = allowedClientIds == null
                ? List.of()
                : allowedClientIds.stream().filter(id -> id != null && !id.isBlank()).toList();
    }

    /** client_id を検証するか。未設定（空）なら検証しない。 */
    public boolean verifiesClientId() {
        return !allowedClientIds.isEmpty();
    }
}
