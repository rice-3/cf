-- 最小権限のアプリ実行用グループロール cf_app_rw（基本設計 §11.4 最小権限、詳細設計 §13.1）。
-- Flyway は DB オーナー（マスター/所有者）として本移行を実行する。アプリの実行時接続を
-- cf_app_rw のメンバーであるログインユーザーへ切り替えることで、アプリからの DDL・破壊操作を防ぐ。
-- ログインユーザーの作成と本ロールの付与は infra/db/create-app-user.sql（資格情報はGit管理外）。
--
-- 本移行はロール作成と権限付与のみで、スキーマ（テーブル）へのDDLは行わない。
-- 冪等: 再実行しても安全（IF NOT EXISTS / GRANT は繰り返し可）。

-- グループロール（NOLOGIN）。ログイン用ユーザーはこのロールのメンバーになる。
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'cf_app_rw') then
        create role cf_app_rw nologin;
    end if;
end
$$;

-- 現行DBへの接続許可（DB名は環境で異なるため current_database() を用いる）。
do $$
begin
    execute format('grant connect on database %I to cf_app_rw', current_database());
end
$$;

-- スキーマ利用と、既存テーブル/シーケンスへの DML（DDLは付与しない）。
grant usage on schema public to cf_app_rw;
grant select, insert, update, delete on all tables in schema public to cf_app_rw;
grant usage, select on all sequences in schema public to cf_app_rw;

-- 以後オーナーが作成するテーブル/シーケンスにも自動で DML を付与し、将来の移行に追従する。
alter default privileges in schema public
    grant select, insert, update, delete on tables to cf_app_rw;
alter default privileges in schema public
    grant usage, select on sequences to cf_app_rw;
