-- 決済Webhook受信履歴（詳細設計 §8.13）
-- webhook_event_idはProvider採番の外部IDを主キーとし、重複受信を一意制約で排除する（§5.4-3）
create table payment_webhook_event (
    webhook_event_id varchar(100) not null,
    provider         varchar(30)  not null,
    event_type       varchar(100) not null,
    payload_hash     varchar(64)  not null,
    payload          jsonb        not null,
    received_at      timestamptz  not null,
    processed_at     timestamptz,
    process_status   varchar(30)  not null,
    retry_count      integer      not null default 0,
    last_error_code  varchar(100),
    constraint pk_payment_webhook_event primary key (webhook_event_id),
    constraint ck_payment_webhook_status check (process_status in ('RECEIVED', 'PROCESSED', 'ERROR'))
);

create index idx_payment_webhook_status on payment_webhook_event (process_status, received_at);
