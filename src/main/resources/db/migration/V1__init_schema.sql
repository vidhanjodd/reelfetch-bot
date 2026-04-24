-- V1__init_schema.sql

CREATE TABLE IF NOT EXISTS bot_users (
    id              BIGSERIAL PRIMARY KEY,
    telegram_id     BIGINT      NOT NULL UNIQUE,
    username        VARCHAR(255),
    first_name      VARCHAR(255),
    last_name       VARCHAR(255),
    is_blocked      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bot_users_telegram_id ON bot_users (telegram_id);

CREATE TABLE IF NOT EXISTS download_logs (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES bot_users(id),
    original_url    TEXT        NOT NULL,
    r2_key          VARCHAR(512),
    file_size_bytes BIGINT,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_download_logs_user_id ON download_logs (user_id);
CREATE INDEX idx_download_logs_status  ON download_logs (status);