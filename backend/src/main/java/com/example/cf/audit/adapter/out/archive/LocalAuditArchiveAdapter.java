package com.example.cf.audit.adapter.out.archive;

import com.example.cf.audit.application.AuditArchivePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 監査アーカイブのローカル実装（BAT-009）。local/test プロファイル限定。
 *
 * <p>外部ストレージへ出力せず、ハッシュと件数の算出のみを行う。
 * dev以上では {@link S3AuditArchiveAdapter} が S3 Glacier Instant Retrieval へ出力する
 * （ADR-0009）。双方のプロファイルを排他にしてBeanの重複を防いでいる。</p>
 *
 * <p>本実装はS3へ出さないがハッシュを返すため、BAT-009 は「出力できた」と判断して
 * DBから削除する。local/test は使い捨てデータなので許容する。</p>
 */
@Component
@Profile({"local", "test"})
public class LocalAuditArchiveAdapter implements AuditArchivePort {

    private static final Logger log = LoggerFactory.getLogger(LocalAuditArchiveAdapter.class);

    private final ObjectMapper objectMapper;

    public LocalAuditArchiveAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String archive(String archiveName, List<Map<String, Object>> rows) {
        String content = objectMapper.writeValueAsString(rows);
        String hash = sha256(content);
        log.info("BAT-009 archived {} rows as {} (sha256={})", rows.size(), archiveName, hash);
        return hash;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
