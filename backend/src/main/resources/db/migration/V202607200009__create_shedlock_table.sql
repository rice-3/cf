-- ShedLock によるスケジュールバッチの多重起動防止（基本設計 §8.3、ADR-0003）。
-- ShedLock標準スキーマ（JdbcTemplateLockProvider）。JPA Entityへはマップしないため
-- ddl-auto: validate の検査対象外。usingDbTime() でDB時刻を用いるため timestamp を使用する。
create table shedlock (
    name       varchar(64)  not null,
    lock_until timestamp    not null,
    locked_at  timestamp    not null,
    locked_by  varchar(255) not null,
    constraint pk_shedlock primary key (name)
);
