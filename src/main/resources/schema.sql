CREATE TABLE IF NOT EXISTS sessions (
    session_id     TEXT PRIMARY KEY,
    agent_type     TEXT NOT NULL,
    container_name TEXT NOT NULL,
    host_port      INTEGER NOT NULL DEFAULT 0,
    persistent     INTEGER NOT NULL DEFAULT 0,
    status         TEXT NOT NULL,
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP NOT NULL
);
