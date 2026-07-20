-- Funding / Payment（詳細設計 §8.10〜§8.12）
create table support (
    support_id        varchar(26)  not null,
    project_id        varchar(26)  not null,
    supporter_user_id varchar(26)  not null,
    support_amount    bigint       not null,
    status            varchar(30)  not null,
    idempotency_key   varchar(100) not null,
    payment_id        varchar(26),
    contact_email     varchar(320) not null,
    version           bigint       not null default 0,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    constraint pk_support primary key (support_id),
    constraint fk_support_project foreign key (project_id) references project (project_id),
    constraint fk_support_supporter foreign key (supporter_user_id) references app_user (user_id),
    constraint uq_support_idempotency unique (supporter_user_id, idempotency_key),
    constraint ck_support_amount check (support_amount > 0)
);

create index idx_support_project_status on support (project_id, status);
create index idx_support_supporter_created on support (supporter_user_id, created_at desc);

create table support_item (
    support_item_id varchar(26) not null,
    support_id      varchar(26) not null,
    reward_plan_id  varchar(26),
    quantity        integer     not null,
    unit_amount     bigint      not null,
    amount          bigint      not null,
    constraint pk_support_item primary key (support_item_id),
    constraint fk_support_item_support foreign key (support_id) references support (support_id),
    constraint fk_support_item_reward foreign key (reward_plan_id) references reward_plan (reward_plan_id),
    constraint ck_support_item_quantity check (quantity > 0),
    constraint ck_support_item_unit_amount check (unit_amount > 0),
    constraint ck_support_item_amount check (amount > 0)
);

create index idx_support_item_support on support_item (support_id);
create index idx_support_item_reward on support_item (reward_plan_id);

create table payment (
    payment_id          varchar(26)  not null,
    support_id          varchar(26)  not null,
    provider            varchar(30)  not null,
    provider_payment_id varchar(100),
    amount              bigint       not null,
    status              varchar(30)  not null,
    failure_code        varchar(100),
    processed_at        timestamptz,
    version             bigint       not null default 0,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    constraint pk_payment primary key (payment_id),
    constraint fk_payment_support foreign key (support_id) references support (support_id),
    constraint uq_payment_support unique (support_id),
    constraint uq_payment_provider unique (provider, provider_payment_id),
    constraint ck_payment_amount check (amount > 0)
);

create index idx_payment_status_updated on payment (status, updated_at);

-- support.payment_id は payment 作成後に設定される（§8.10 FK/UQ）
alter table support
    add constraint fk_support_payment foreign key (payment_id) references payment (payment_id);
alter table support
    add constraint uq_support_payment unique (payment_id);
