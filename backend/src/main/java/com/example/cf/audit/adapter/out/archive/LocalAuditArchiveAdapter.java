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
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 監査アーカイブのローカル実装（BAT-009）。
 *
 * <p>教育環境では外部ストレージへ出力せず、ハッシュと件数の算出のみを行う。
 * dev以上の環境ではS3 Glacier相当へ出力するAdapterへ差し替える。</p>
 *
 * <p>TODO(question): 実際のアーカイブ出力先（S3バケット・ストレージクラス・保持年数）が
 * 未確定のため、本実装は出力を伴わない。運用要件の確定後に差し替えること。</p>
 */
@Component
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
