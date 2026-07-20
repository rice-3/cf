-- Review（詳細設計 §8.8〜§8.9）
create table review_request (
    review_id        varchar(26) not null,
    project_id       varchar(26) not null,
    status           varchar(30) not null,
    reviewer_user_id varchar(26),
    submitted_at     timestamptz not null,
    started_at       timestamptz,
    completed_at     timestamptz,
    version          bigint      not null default 0,
    constraint pk_review_request primary key (review_id),
    constraint fk_review_request_project foreign key (project_id) references project (project_id),
    constraint fk_review_request_reviewer foreign key (reviewer_user_id) references app_user (user_id),
    constraint ck_review_request_status check (
        status in ('REQUESTED', 'UNDER_REVIEW', 'APPROVED', 'RETURNED', 'REJECTED', 'WITHDRAWN')
    )
);

create index idx_review_request_status_submitted on review_request (status, submitted_at);

-- 同一プロジェクトのアクティブ審査は1件のみ（部分一意索引、§8.8）
create unique index uq_review_request_active_project
    on review_request (project_id)
    where status in ('REQUESTED', 'UNDER_REVIEW');

create table review_history (
    review_history_id varchar(26)  not null,
    review_id         varchar(26)  not null,
    action            varchar(30)  not null,
    reason_code       varchar(50),
    comment           varchar(2000),
    checklist_json    jsonb,
    acted_at          timestamptz  not null,
    acted_by          varchar(26)  not null,
    constraint pk_review_history primary key (review_history_id),
    constraint fk_review_history_review foreign key (review_id) references review_request (review_id),
    constraint ck_review_history_action check (action in ('START', 'APPROVE', 'RETURN', 'REJECT'))
);

create index idx_review_history_review on review_history (review_id, acted_at desc);
