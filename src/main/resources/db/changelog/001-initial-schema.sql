--liquibase formatted sql

-- Every id is a prefixed string drawn from a per-entity sequence, matching the ids the
-- spec shows literally: usr_101, proj_1, msg_201. The sequences start where those
-- examples start so the seeded rows reproduce them exactly.

--changeset arman:001-users
--comment: Accounts. Backs UserProfile, TeamMember and the login credentials.
CREATE SEQUENCE users_id_seq START WITH 101 INCREMENT BY 1;

CREATE TABLE users (
    id                    VARCHAR(64)  PRIMARY KEY,
    student_id            VARCHAR(64)  NOT NULL UNIQUE,
    name                  VARCHAR(255) NOT NULL,
    email                 VARCHAR(320) NOT NULL UNIQUE,
    password_hash         VARCHAR(255) NOT NULL,
    role                  VARCHAR(16)  NOT NULL,
    avatar                VARCHAR(1024),
    theme                 VARCHAR(16)  NOT NULL DEFAULT 'light',
    language              VARCHAR(8)   NOT NULL DEFAULT 'fa',
    notifications_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    -- TeamMember.status: the spec declares it a free string with no enum, so no CHECK.
    status                VARCHAR(32)  NOT NULL DEFAULT 'active',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT users_role_check     CHECK (role     IN ('admin', 'student')),
    CONSTRAINT users_theme_check    CHECK (theme    IN ('light', 'dark')),
    CONSTRAINT users_language_check CHECK (language IN ('fa', 'en'))
);
--rollback DROP TABLE users;
--rollback DROP SEQUENCE users_id_seq;

--changeset arman:002-projects
--comment: Projects. `createdAt` in the spec is a string; stored as a real timestamp.
CREATE SEQUENCE projects_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE projects (
    id          VARCHAR(64)  PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    description TEXT,
    color       VARCHAR(32),
    icon        VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
--rollback DROP TABLE projects;
--rollback DROP SEQUENCE projects_id_seq;

--changeset arman:003-tasks
--comment: Tasks. projectId is required by CreateTaskRequest, hence NOT NULL.
CREATE SEQUENCE tasks_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE tasks (
    id          VARCHAR(64)  PRIMARY KEY,
    -- DELETE /api/projects/{id} is unqualified in the spec. Cascading is the only
    -- behaviour that does not turn a documented 200 into an FK-violation 500.
    project_id  VARCHAR(64)  NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    title       VARCHAR(255) NOT NULL,
    description TEXT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'todo',
    priority    VARCHAR(16),
    -- The spec types dueDate as a string but examples it as "2026-08-15".
    due_date    DATE,
    CONSTRAINT tasks_status_check   CHECK (status IN ('todo', 'in_progress', 'review', 'done')),
    CONSTRAINT tasks_priority_check CHECK (priority IS NULL OR priority IN ('low', 'medium', 'high'))
);

-- GET /api/tasks filters on exactly these two columns.
CREATE INDEX idx_tasks_project_id ON tasks (project_id);
CREATE INDEX idx_tasks_status     ON tasks (status);
--rollback DROP TABLE tasks;
--rollback DROP SEQUENCE tasks_id_seq;

--changeset arman:004-task-assignees
--comment: Task.assignees is an array of user ids, so it needs its own table.
CREATE TABLE task_assignees (
    task_id VARCHAR(64) NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, user_id)
);

CREATE INDEX idx_task_assignees_user_id ON task_assignees (user_id);
--rollback DROP TABLE task_assignees;

--changeset arman:005-weekly-reports
--comment: Weekly reports. userName is snapshotted, not joined -- a submitted report is
--comment: a historical record and must keep the name it was filed under.
CREATE SEQUENCE weekly_reports_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE weekly_reports (
    id              VARCHAR(64)  PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_name       VARCHAR(255) NOT NULL,
    week_title      VARCHAR(255) NOT NULL,
    hours_worked    NUMERIC(6, 2) NOT NULL,
    tasks_completed INTEGER      NOT NULL,
    achievements    TEXT,
    challenges      TEXT,
    next_week_plan  TEXT,
    submitted_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_weekly_reports_user_id      ON weekly_reports (user_id);
CREATE INDEX idx_weekly_reports_submitted_at ON weekly_reports (submitted_at DESC);
--rollback DROP TABLE weekly_reports;
--rollback DROP SEQUENCE weekly_reports_id_seq;

--changeset arman:006-chat-messages
--comment: Chat history. senderName/senderAvatar are snapshotted for the same reason as
--comment: weekly_reports.user_name, and so the SSE broadcast needs no join per message.
--comment: The column is sent_at, not `timestamp`, to avoid the SQL type keyword.
CREATE SEQUENCE chat_messages_id_seq START WITH 201 INCREMENT BY 1;

CREATE TABLE chat_messages (
    id            VARCHAR(64)  PRIMARY KEY,
    sender_id     VARCHAR(64)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sender_name   VARCHAR(255) NOT NULL,
    sender_avatar VARCHAR(1024),
    content       TEXT         NOT NULL,
    file_url      VARCHAR(1024),
    sent_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_sent_at ON chat_messages (sent_at);
--rollback DROP TABLE chat_messages;
--rollback DROP SEQUENCE chat_messages_id_seq;

--changeset arman:007-refresh-tokens
--comment: The spec's refreshToken example is a bare UUID, not a JWT. An opaque UUID
--comment: carries no signature, so it can only be validated by lookup -- which settles
--comment: decision D8 in favour of a persisted table.
CREATE TABLE refresh_tokens (
    token      UUID        PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
--rollback DROP TABLE refresh_tokens;
