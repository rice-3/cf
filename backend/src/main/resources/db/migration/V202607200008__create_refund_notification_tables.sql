-- Refund / Notification（詳細設計 §8.14 / §8.16 / §8.17）
create table refund (
    refund_id          varchar(26)  not null,
    payment_id         varchar(26)  not null,
    support_id         varchar(26)  not null,
    amount             bigint       not null,
    reason_code        varchar(50)  not null,
    comment            varchar(2000),
    status             varchar(30)  not null,
    provider_refund_id varchar(100),
    retry_count        integer      not null default 0,
    next_retry_at      timestamptz,
    version            bigint       not null default 0,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    constraint pk_refund primary key (refund_id),
    constraint fk_refund_payment foreign key (payment_id) references payment (payment_id),
    constraint fk_refund_support foreign key (support_id) references support (support_id),
    constraint uq_refund_provider unique (provider_refund_id),
    constraint ck_refund_amount check (amount > 0),
    constraint ck_refund_status check (status in ('REQUESTED', 'PROCESSING', 'SUCCEEDED', 'RETRY_WAIT', 'FAILED')),
    constraint ck_refund_reason check (reason_code in ('PROJECT_FAILED', 'OPERATIONAL', 'USER_CANCEL'))
);

create index idx_refund_status_retry on refund (status, next_retry_at);
create index idx_refund_support on refund (support_id);

-- 1支援につき有効な返金は1件（§6.9 REFUND_ALREADY_EXISTS）。
-- 恒久失敗（FAILED）後の再作成は許可するため部分一意インデックスとする。
-- BAT-003がProjectFailedを重複受信しても二重作成されない。
create unique index uq_refund_active_support on refund (support_id) where status <> 'FAILED';

create table notification (
    notification_id   varchar(26)  not null,
    business_key      varchar(200) not null,
    channel           varchar(30)  not null,
    template_id       varchar(100) not null,
    recipient_user_id varchar(26),
    recipient_address varchar(320),
    variables         jsonb        not null default '{}'::jsonb,
    status            varchar(30)  not null,
    retry_count       integer      not null default 0,
    next_retry_at     timestamptz,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    constraint pk_notification primary key (notification_id),
    constraint fk_notification_recipient foreign key (recipient_user_id) references app_user (user_id),
    -- 重複防止（詳細設計 §4.6）。同一業務事象・同一チャネルの通知は1件のみ。
    constraint uq_notification_business_key unique (business_key, channel),
    constraint ck_notification_channel check (channel in ('EMAIL', 'IN_APP')),
    constraint ck_notification_status check (status in ('PENDING', 'SENDING', 'SENT', 'RETRY_WAIT', 'FAILED'))
);

create index idx_notification_status_retry on notification (status, next_retry_at);
create index idx_notification_recipient on notification (recipient_user_id, created_at desc);

create table notification_delivery (
    delivery_id         varchar(26)  not null,
    notification_id     varchar(26)  not null,
    attempt_no          integer      not null,
    provider_message_id varchar(200),
    result              varchar(30)  not null,
    error_code          varchar(100),
    attempted_at        timestamptz  not null,
    constraint pk_notification_delivery primary key (delivery_id),
    constraint fk_notification_delivery_notification
        foreign key (notification_id) references notification (notification_id),
    constraint ck_notification_delivery_result check (result in ('SUCCESS', 'FAILURE'))
);

create index idx_notification_delivery_notification on notification_delivery (notification_id, attempt_no);
