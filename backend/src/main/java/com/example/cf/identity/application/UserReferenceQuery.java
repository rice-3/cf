package com.example.cf.identity.application;

/**
 * Identityコンテキスト公開契約（基本設計 §4.1）。
 * 他コンテキストは会員状態をこのQuery経由でのみ確認する。
 *
 * <p>Identity系はJava実装とする（詳細設計 §2.2 言語配置方針）。
 * 会員管理API（API-US/AD系）は工程9で実装する。</p>
 */
public interface UserReferenceQuery {

    /** 利用者が存在しACTIVE状態であるかを返す（§5.3: 会員ACTIVE検証）。 */
    boolean isActive(String userId);

    /**
     * 通知の宛先メールアドレスを返す。存在しない場合はnull。
     * 呼出し側はこの値をログへ平文出力してはならない（§10.3）。
     */
    String findEmail(String userId);
}
