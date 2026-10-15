-- A phone number belongs to at most one internal account, whatever its status (active, pending activation, locked or
-- disabled), like the email. Every write path stores the number already normalized (+84 becomes 0, the Vietnamese
-- pattern is checked), so a plain unique constraint on the column compares equal numbers. PostgreSQL treats NULLs as
-- distinct, so any number of accounts may still have no phone. The services check first and answer
-- 409 PHONE_ALREADY_EXISTS; this constraint decides when two saves race after that check.
--
-- An existing database where some numbers are already shared stops here instead of guessing which account keeps the
-- number. The error gives only how many numbers are shared, never the numbers themselves, so no phone number reaches
-- the startup log. Run the duplicate check of docs/api/profile.md before upgrading and fix those accounts first.
DO $$
DECLARE
    duplicated INTEGER;
BEGIN
    SELECT count(*) INTO duplicated
    FROM (SELECT phone FROM user_accounts WHERE phone IS NOT NULL GROUP BY phone HAVING count(*) > 1) shared;
    IF duplicated > 0 THEN
        RAISE EXCEPTION
            'V38: % so dien thoai dang dung cho nhieu tai khoan; hay sua du lieu truoc khi nang cap (docs/api/profile.md)',
            duplicated;
    END IF;
END $$;

ALTER TABLE user_accounts ADD CONSTRAINT user_accounts_phone_key UNIQUE (phone);
