-- Identity & Access（詳細設計 §8.2〜§8.4）
create table app_user (
    user_id         varchar(26)  not null,
    cognito_subject varchar(100) not null,
    email           varchar(320) not null,
    display_name    varchar(100) not null,
    status          varchar(30)  not null,
    version         bigint       not null default 0,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    constraint pk_app_user primary key (user_id),
    constraint uq_app_user_cognito_subject unique (cognito_subject),
    constraint ck_app_user_status check (status in ('ACTIVE', 'SUSPENDED', 'WITHDRAWN'))
);

create unique index uq_app_user_email_lower on app_user (lower(email));
create index idx_app_user_status on app_user (status);

create table role (
    role_code    varchar(30)  not null,
    display_name varchar(100) not null,
    assignable   boolean      not null default true,
    constraint pk_role primary key (role_code)
);

create table user_role (
    user_id     varchar(26) not null,
    role_code   varchar(30) not null,
    assigned_at timestamptz not null,
    assigned_by varchar(26) not null,
    constraint pk_user_role primary key (user_id, role_code),
    constraint fk_user_role_user foreign key (user_id) references app_user (user_id),
    constraint fk_user_role_role foreign key (role_code) references role (role_code)
);

create index idx_user_role_role_code on user_role (role_code);

-- ロールマスタ初期データ（基本設計 §14.3。認証情報は含めない）
insert into role (role_code, display_name, assignable) values
    ('GUEST',     'ゲスト',         false),
    ('SUPPORTER', '支援者',         true),
    ('OWNER',     '起案者',         true),
    ('REVIEWER',  '審査担当者',     true),
    ('OPERATOR',  '運用担当者',     true),
    ('ADMIN',     'システム管理者', true),
    ('AUDITOR',   '監査担当者',     true),
    ('DEVELOPER', '開発者',         true),
    ('AI_AGENT',  'AIエージェント', false);
