-- Administrative locks are independent of activation and the temporary login lock.
-- NULL metadata keeps every existing account in its previous state.
ALTER TABLE user_accounts
    ADD COLUMN admin_locked_at TIMESTAMPTZ,
    ADD COLUMN admin_lock_reason VARCHAR(500),
    ADD COLUMN admin_locked_by UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    ADD CONSTRAINT complete_administrative_account_lock CHECK (
        (admin_locked_at IS NULL AND admin_lock_reason IS NULL AND admin_locked_by IS NULL)
        OR (admin_locked_at IS NOT NULL AND admin_lock_reason IS NOT NULL AND admin_locked_by IS NOT NULL)
    ),
    ADD CONSTRAINT valid_administrative_account_lock_reason CHECK (
        admin_lock_reason IS NULL
        OR (admin_lock_reason <> '' AND admin_lock_reason !~ '^[[:space:]]|[[:space:]]$')
    );
