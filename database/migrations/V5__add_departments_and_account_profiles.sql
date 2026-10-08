CREATE TABLE departments (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    parent_id UUID REFERENCES departments(id) ON DELETE RESTRICT,
    manager_user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT valid_department_code CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT valid_department_name CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT department_parent_not_self CHECK (parent_id <> id)
);

CREATE INDEX departments_parent_id_idx ON departments(parent_id);
CREATE INDEX departments_manager_user_id_idx ON departments(manager_user_id);

-- Nullable fields preserve existing accounts until their profiles are updated.
ALTER TABLE user_accounts
    ADD COLUMN phone VARCHAR(20),
    ADD COLUMN display_title VARCHAR(120),
    ADD COLUMN department_id UUID REFERENCES departments(id) ON DELETE RESTRICT;

CREATE INDEX user_accounts_department_id_idx ON user_accounts(department_id);

-- Multi-level cycle checks and checks for open requisitions belong to the
-- department management service (tasks 196/197), which is not implemented yet.
INSERT INTO permissions (code, module_code, action_code, scope_code)
VALUES ('SELF_PROFILE_WRITE', 'SELF_PROFILE', 'WRITE', 'SCOPED');

INSERT INTO role_permissions (role_code, permission_code)
SELECT code, 'SELF_PROFILE_WRITE' FROM roles WHERE internal;
