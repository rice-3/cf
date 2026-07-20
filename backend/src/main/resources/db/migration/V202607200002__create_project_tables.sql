-- Project Management（詳細設計 §8.5〜§8.7）
create table project (
    project_id    varchar(26)  not null,
    owner_user_id varchar(26)  not null,
    title         varchar(100) not null,
    summary       varchar(300) not null,
    body          text         not null,
    target_amount bigint       not null,
    funding_type  varchar(30)  not null,
    start_at      timestamptz  not null,
    end_at        timestamptz  not null,
    status        varchar(30)  not null,
    main_file_id  varchar(26),
    version       bigint       not null default 0,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint pk_project primary key (project_id),
    constraint fk_project_owner foreign key (owner_user_id) references app_user (user_id),
    constraint ck_project_target_amount check (target_amount >= 1000),
    constraint ck_project_period check (end_at > start_at),
    constraint ck_project_funding_type check (funding_type in ('ALL_OR_NOTHING', 'ALL_IN'))
);

create index idx_project_status_period on project (status, start_at, end_at);
create index idx_project_owner_updated on project (owner_user_id, updated_at desc);

create table reward_plan (
    reward_plan_id    varchar(26)   not null,
    project_id        varchar(26)   not null,
    name              varchar(100)  not null,
    description       varchar(2000) not null,
    unit_amount       bigint        not null,
    quantity_limit    integer,
    reserved_quantity integer       not null default 0,
    display_order     integer       not null,
    version           bigint        not null default 0,
    constraint pk_reward_plan primary key (reward_plan_id),
    constraint fk_reward_plan_project foreign key (project_id) references project (project_id),
    constraint ck_reward_plan_unit_amount check (unit_amount > 0),
    constraint ck_reward_plan_quantity_limit check (quantity_limit is null or quantity_limit > 0),
    constraint ck_reward_plan_reserved check (
        reserved_quantity >= 0
        and (quantity_limit is null or reserved_quantity <= quantity_limit)
    )
);

create index idx_reward_plan_project_order on reward_plan (project_id, display_order);

create table project_status_history (
    history_id  varchar(26) not null,
    project_id  varchar(26) not null,
    from_status varchar(30),
    to_status   varchar(30) not null,
    reason      varchar(2000),
    changed_at  timestamptz not null,
    changed_by  varchar(26),
    constraint pk_project_status_history primary key (history_id),
    constraint fk_project_status_history_project foreign key (project_id) references project (project_id)
);

create index idx_project_status_history_project on project_status_history (project_id, changed_at desc);
