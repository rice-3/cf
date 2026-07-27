package com.example.cf.audit.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 監査アーカイブ（BAT-009）の出力設定（ADR-0009、基本設計 §7.7）。
 *
 * @param bucket       出力先バケット。dev以上は `CF_AUDIT_ARCHIVE_BUCKET` で注入する
 * @param keyPrefix    S3キーの先頭に付く環境識別子
 * @param storageClass 出力時に指定するストレージクラス。保持は1年（S3側ライフサイクル）なので、
 *                     最低保存期間90日の GLACIER_IR を既定とする。DEEP_ARCHIVE は安いが
 *                     取り出しに数時間かかり、監査調査の実用性を損なう
 */
@ConfigurationProperties(prefix = "cf.audit.archive")
public record AuditArchiveProperties(String bucket, String keyPrefix, String storageClass) {

    public AuditArchiveProperties {
        keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "local" : keyPrefix;
        storageClass = storageClass == null || storageClass.isBlank() ? "GLACIER_IR" : storageClass;
    }
}
