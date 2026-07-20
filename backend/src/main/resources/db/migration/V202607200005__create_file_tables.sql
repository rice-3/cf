-- File Management（詳細設計 §8.15）
create table file_object (
    file_id       varchar(26)   not null,
    owner_user_id varchar(26)   not null,
    purpose       varchar(50)   not null,
    s3_bucket     varchar(63)   not null,
    s3_key        varchar(1024) not null,
    original_name varchar(255)  not null,
    content_type  varchar(100)  not null,
    size_bytes    bigint        not null,
    -- 設計書§8.15はchar(64)だが、Hibernateスキーマ検証（ddl-auto: validate）と
    -- 型を一致させるためvarchar(64)とする（§8.21 request_hashと同方針）
    sha256        varchar(64)   not null,
    status        varchar(30)   not null,
    expires_at    timestamptz,
    created_at    timestamptz   not null,
    updated_at    timestamptz   not null,
    constraint pk_file_object primary key (file_id),
    constraint fk_file_object_owner foreign key (owner_user_id) references app_user (user_id),
    constraint uq_file_object_s3_key unique (s3_key),
    constraint ck_file_object_status check (status in ('PENDING', 'COMPLETE', 'DELETED')),
    constraint ck_file_object_size check (size_bytes between 1 and 10485760)
);

create index idx_file_object_owner on file_object (owner_user_id, status);
-- BAT-008 ファイル清掃（未完了・期限切れ）の走査用
create index idx_file_object_expires on file_object (status, expires_at);
