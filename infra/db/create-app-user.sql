-- アプリ実行用ログインユーザーの作成（最小権限グループ cf_app_rw を付与）。
--
-- 目的（基本設計 §11.4 最小権限）: アプリの実行時接続を、DDL権限を持たない専用ユーザーに分離する。
-- スキーマ移行（Flyway/DDL）は引き続きオーナー（RDSマスター = cf_app）が実行し、アプリ実行時は
-- 本ユーザーで接続する。グループロール cf_app_rw と権限は Flyway 移行
-- V202607230001__create_app_runtime_role.sql が作成する（本スクリプトの前に適用済みであること）。
--
-- 資格情報はGitに置かない。psql変数で渡す:
--   PGPASSWORD=<owner_pw> psql -h <host> -U cf_app -d <db> \
--     -v app_user=cf_app_login -v app_password="$(openssl rand -base64 24)" \
--     -f infra/db/create-app-user.sql
--
-- 本番: 生成パスワードを Secrets Manager に保存し、アプリの spring.datasource.username/password へ、
--       Flyway は spring.flyway.user/spring.flyway.password（オーナー）へ設定する（README参照）。

\set ON_ERROR_STOP on

-- 冪等: 既存なら LOGIN/パスワードを更新、無ければ作成（psqlの \if は dollar-quote 制約を避けられる）。
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') AS need_create \gset
\if :need_create
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';
\else
ALTER ROLE :"app_user" WITH LOGIN PASSWORD :'app_password';
\endif

-- 最小権限グループのメンバーにする（DMLのみ、DDL不可）。
GRANT cf_app_rw TO :"app_user";

\echo 'created/updated app login user and granted cf_app_rw'
