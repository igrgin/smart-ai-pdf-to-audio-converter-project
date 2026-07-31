create table platform_status (
    singleton boolean primary key default true,
    started_at timestamp with time zone not null default current_timestamp,
    constraint platform_status_singleton check (singleton)
);

insert into platform_status (singleton) values (true);
