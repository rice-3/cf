-- Shared: Outbox / Audit / 冪等記録（詳細設計 §8.18〜§8.21）
-- 注: 設計書§8.23ではfunding/paymentがV...0004だが、第1段階では未実装のため
--     本ファイルを0004とする（Flywayのout-of-order適用を避けるため。ADR記録済み方針）。

create table outbox_event (
    event_id       varchar(26)  not null,
    aggregate_type varchar(100) not null,
    aggregate_id   varchar(26)  not null,
    event_type     varchar(200) not null,
    payload        jsonb        not null,
    occurred_at    timestamptz  not null,
    publish_status varchar(30)  not null default 'PENDING',
    retry_count    integer      not null default 0,
    next_retry_at  timestamptz,
    published_at   timestamptz,
    constraint pk_outbox_event primary key (event_id),
    constraint ck_outbox_publish_status check (publish_status in ('PENDING', 'PUBLISHED', 'ERROR'))
);

create index idx_outbox_event_pending on outbox_event (publish_status, next_retry_at, occurred_at);

create table audit_log (
    audit_id       varchar(26)  not null,
    occurred_at    timestamptz  not null,
    actor_user_id  varchar(26),
    action         varchar(100) not null,
    resource_type  varchar(100) not null,
    resource_id    varchar(100),
    result         varchar(30)  not null,
    correlation_id varchar(64)  not null,
    detail         jsonb        not null default '{}'::jsonb,
    client_ip_hash varchar(64),
    constraint pk_audit_log primary key (audit_id)
);

create index idx_audit_log_occurred on audit_log (occurred_at desc);
create index idx_audit_log_actor on audit_log (actor_user_id, occurred_at desc);
create index idx_audit_log_resource on audit_log (resource_type, resource_id);

create table ai_activity_log (
    ai_activity_id varchar(26)  not null,
    occurred_at    timestamptz  not null,
    actor_user_id  varchar(26)  not null,
    tool_name      varchar(100) not null,
    task_id        varchar(100),
    action_type    varchar(50)  not null,
    repository     varchar(200) not null,
    changed_paths  jsonb        not null default '[]'::jsonb,
    prompt_hash    varchar(64),
    result         varchar(30)  not null,
    approved_by    varchar(26),
    constraint pk_ai_activity_log primary key (ai_activity_id)
);

create index idx_ai_activity_occurred on ai_activity_log (occurred_at desc);
create index idx_ai_activity_actor on ai_activity_log (actor_user_id, occurred_at desc);

create table idempotency_record (
    scope           varchar(100) not null,
    actor_id        varchar(100) not null,
    idempotency_key varchar(100) not null,
    request_hash    varchar(64)     not null,
    status          varchar(30)  not null,
    response_status integer,
    response_body   jsonb,
    expires_at      timestamptz  not null,
    created_at      timestamptz  not null,
    constraint pk_idempotency_record primary key (scope, actor_id, idempotency_key),
    constraint ck_idempotency_status check (status in ('PROCESSING', 'COMPLETED', 'FAILED'))
);

create index idx_idempotency_expires on idempotency_record (expires_at);
