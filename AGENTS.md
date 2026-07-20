# AGENTS.md — AIコーディングエージェント規約（CF-Training）

本ファイルは詳細設計書 DD-CF-001 §15.2 に基づく。AIエージェントは本規約に従うこと。

## プロジェクト目的

教育用クラウドファンディング型Webサービス。実決済・実顧客情報は扱わない。
AIが生成し、人間が判断・承認・責任を持つ。

## 技術スタック（固定）

- Amazon Corretto 25（Java/Kotlin とも JVM target 25 で統一）
- Kotlin 2.4系（主言語）/ Java 25（副言語）/ Spring Boot 4.1 / Spring Framework 7
- Gradle 9.6 Wrapper（ローカルGradleへ依存しない）
- PostgreSQL 18 / Flyway / Spring Data JPA
- Next.js 16.2 / React 19 / TypeScript 5 / Node.js 24 LTS

## DDD境界と依存規則

- コンテキスト: identity / project / review / funding / payment / notification / file / audit / shared
- 依存方向: `adapter → application → domain`。逆流禁止。
- domain層へ Spring / JPA / MyBatis / AWS SDK / HTTP クライアントの型を持ち込まない。
- 他コンテキストへは公開契約（application API・ドメインイベント）経由のみ。Repository・テーブル直接参照禁止。
- 違反は ArchUnit テスト（`backend/src/test/kotlin/.../architecture/`）で検出される。

## コーディング規約

- 詳細設計書 §1.2 命名規約、§1.3 Null・型・金額・日時、§1.4 コーディング制約に従う。
- 金額は `Money`、IDは型付きVO。`Instant.now()` 直接呼出し禁止（`Clock` 注入）。
- 状態変更は意味のあるドメインメソッドで行う。setter禁止。
- EntityをAPIレスポンスへ直接シリアライズしない。

## ビルド・テスト実行方法

```bash
cd backend && ./gradlew build      # コンパイル + 単体 + ArchUnit
cd backend && ./gradlew check      # 統合テスト含む（Docker必須）
cd frontend && npm run lint && npm run build
```

## セキュリティ上の禁止事項

- 秘密情報（トークン・パスワード・秘密鍵）・実在個人情報をコード・ログ・プロンプトへ含めない。
- `.env`、`*.pem`、Secrets Manager 参照値を読み取り・出力しない。
- 既存のセキュリティ制御（認可・署名検証・CSRF対策）を無断で解除しない。

## 変更してはならない領域

- `backend/src/main/resources/db/migration/` の適用済みMigration（修正は新規Vファイルで）
- `docs/adr/` の承認済みADR（変更は新規ADRで置換）
- CI品質ゲート定義（`.github/workflows/`）の検査スキップ・削除

## 完了条件（Definition of Done 抜粋）

1. Corretto 25でビルド成功、Java/Kotlin JVMターゲット一致
2. 単体・必要な結合テスト追加、CI成功
3. DDD境界・依存方向に違反しない（ArchUnit成功）
4. API変更時は OpenAPI 更新、DB変更時は Flyway スクリプト追加
5. 人間によるレビュー・承認（AI単独マージ禁止）

## 不明点の扱い

仕様の不明点を推測で補わないこと。未確定事項はコード内 `TODO(question):` コメントと
PR説明の質問一覧に残し、人間の判断を仰ぐ。
