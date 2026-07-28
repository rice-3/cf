package com.example.cf.audit.adapter.out.archive;

import com.example.cf.audit.application.AuditArchivePort;
import com.example.cf.audit.application.AuditArchiveProperties;
import com.example.cf.shared.kernel.error.DependencyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.StorageClass;
import tools.jackson.databind.ObjectMapper;

/**
 * 監査アーカイブのS3実装（BAT-009、ADR-0009）。dev以上の環境で使用する。
 *
 * <p>タスクロールには {@code s3:PutObject} のみ与えてあり、Get も Delete も持たない
 * （基本設計 §7.7「改ざん防止、参照権限限定」）。したがって<strong>書き込んだ内容を
 * 読み直して検証することはできない</strong>。代わりに PUT 時へ SHA-256 チェックサムを
 * 添えてS3側で検証させる。不一致ならS3が PUT 自体を拒否するので、
 * 「返ってきたハッシュ = S3が受理した内容のハッシュ」が保証される。</p>
 *
 * <p>出力に失敗した場合は {@link DependencyException} を投げる。BAT-009 は
 * ハッシュを得られない限りDBを削除しないため、次回実行へ持ち越される。</p>
 */
@Component
@Profile("!local & !test")
public class S3AuditArchiveAdapter implements AuditArchivePort, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(S3AuditArchiveAdapter.class);

    private final AuditArchiveProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * S3クライアントは遅延生成する。BAT-009 は月次でしか動かないため常時保持する必要が無く、
     * 何より<strong>設定不備の検知（bucket未設定）をAWSへ触れる前に行える</strong>ようにするため。
     * コンストラクタで {@code S3Client.create()} すると、リージョン未解決の環境では
     * 生成時点で落ちてしまい、下の設定ガードまで到達しない。
     */
    private volatile S3Client s3;

    public S3AuditArchiveAdapter(AuditArchiveProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private S3Client client() {
        S3Client current = s3;
        if (current == null) {
            synchronized (this) {
                current = s3;
                if (current == null) {
                    current = S3Client.create();
                    s3 = current;
                }
            }
        }
        return current;
    }

    @Override
    public String archive(String archiveName, List<Map<String, Object>> rows) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            // 出力先が無いのにハッシュを返すとBAT-009がDBを削除してしまう。必ず失敗させる。
            throw new DependencyException("AUDIT_ARCHIVE_NOT_CONFIGURED",
                    "cf.audit.archive.bucket is not configured (CF_AUDIT_ARCHIVE_BUCKET)", null);
        }

        byte[] content = objectMapper.writeValueAsString(rows).getBytes(StandardCharsets.UTF_8);
        byte[] digest = sha256(content);
        String key = objectKey(archiveName);

        PutObjectRequest request = PutObjectRequest.builder().bucket(properties.bucket()).key(key).contentType("application/json")
                // S3側で内容を検証させる。不一致ならPUTが失敗する。
                .checksumSHA256(Base64.getEncoder().encodeToString(digest)).storageClass(StorageClass.fromValue(properties.storageClass()))
                .build();

        try {
            client().putObject(request, RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new DependencyException("AUDIT_ARCHIVE_UNAVAILABLE", "Failed to put audit archive: " + key, e);
        }

        String hash = HexFormat.of().formatHex(digest);
        log.info("BAT-009 archived {} rows to s3://{}/{} (storageClass={} sha256={})", rows.size(), properties.bucket(), key,
                properties.storageClass(), hash);
        return hash;
    }

    /**
     * 出力キー。実行時刻を含めることで、同一 archiveName で再実行しても既存を上書きしない
     * （バケットはバージョニング有効だが、そもそも上書きさせない）。
     * archiveName には ISO-8601 の時刻が含まれ `:` を持つため、キーとして扱いやすい形へ置換する。
     */
    private String objectKey(String archiveName) {
        String safeName = archiveName.replace(':', '-');
        return "%s/%s/%s.json".formatted(properties.keyPrefix(), safeName, clock.instant().toString().replace(':', '-'));
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @Override
    public void destroy() {
        S3Client current = s3;
        if (current != null) {
            current.close();
        }
    }
}
