# ADR-0004: フロントエンドを Next.js による BFF 構成とする

## 状態

採用

## 背景

基本設計 §5.2 / §10 は、フロントエンドを Next.js 16（App Router）とし、認証は本番で Cognito OIDC、
バックエンドAPIはトークン認証（Resource Server）と定める。ブラウザ〜バックエンド間の呼び出し方式と
認証情報の保持場所を決める必要がある。

選択肢:

1. SPA がブラウザから直接バックエンドAPIを呼ぶ（アクセストークンをブラウザJSが保持）
2. **Next.js を BFF（Backend for Frontend）とし、Server Component / Server Action からのみ
   バックエンドを呼ぶ**（トークン・セッションはサーバー側のみ）
3. 独立した BFF サービスを別途構築する

## 判断

**選択肢2（Next.js を BFF とする）** を採用する。

## 判断理由

- 認証情報をブラウザに露出しない（§7.9）。開発用セッションは **HttpOnly Cookie**（`cf_dev_user`）で保持し、
  ブラウザJSからトークン相当を参照させない。本番は BFF が Cognito セッションを保持し、バックエンドへは
  サーバー間でJWTを付与する。
- App Router の Server Component / Server Action がサーバー実行の自然な境界となり、追加のBFFサービス
  （選択肢3）を運用せずに単一のフロントデプロイで完結する。
- ロール判定・認証ヘッダー変換をサーバー側に集約できる。local は BFF が `X-Dev-User` / `X-Dev-Roles` へ
  変換し、dev以上は JWT を Resource Server が検証する（`DevHeaderAuthenticationFilter` /
  `ResourceServerSecurityConfig`）。
- CSRF・トークン漏洩の攻撃面をブラウザから排除できる（選択肢1の主要リスクを回避）。

## 適用範囲

- サーバー専用処理は `lib/backend.ts`（`server-only`、`BACKEND_URL` へfetch）に集約する。
- クライアント/サーバー共用の型・定数は `lib/api-types.ts` に分離し、Client Component からは
  `next/headers` 依存を import しない（ビルド境界エラーの防止、詳細設計の落とし穴一覧参照）。
- ログイン/ログアウトは Server Action（`session-actions.ts`）。

## 結果

- `lib/backend.ts`（server-only BFFフェッチ + Problem Details 変換）、`lib/api-types.ts`（client-safe）、
  `session-actions.ts`、`DevHeaderAuthenticationFilter`。
- 全19画面が Server Component 主体で実装され、E2E（`e2e.yml`）とCIで表示・認可・主要フローを検証済み。
- 本番の Cognito OIDC 実結合確認は残タスク（要判断事項E、dev環境構築時）。

## AI利用

Claude（Opus 4.8）が3案を比較提示し、人間が選択肢2を選定。実装・本ADR起草をClaudeが行った。

## 承認者

（教育担当者の承認欄）
