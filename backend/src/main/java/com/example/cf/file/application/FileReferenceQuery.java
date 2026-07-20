package com.example.cf.file.application;

/**
 * Fileコンテキスト公開契約（基本設計 §4.1）。
 * 他コンテキストはファイルの完了状態をこのQuery経由でのみ確認する。
 *
 * <p>Fileコンテキストは第2段階でS3連携（API-FL-001/002）とともに本実装する。
 * 第1段階はスタブ実装（{@code local}プロファイル）を使用する。</p>
 */
public interface FileReferenceQuery {

    /**
     * ファイルがCOMPLETE状態で、指定利用者の所有物であるかを返す。
     *
     * @param fileId      ファイルID（ULID）
     * @param ownerUserId 所有者の内部UserId
     */
    boolean isCompletedAndOwnedBy(String fileId, String ownerUserId);
}
