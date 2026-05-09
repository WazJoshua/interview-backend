UPDATE users
SET locale = 'zh-CN'
WHERE locale IS NULL
   OR BTRIM(locale) = ''
   OR locale NOT IN ('zh-CN', 'en-US');

ALTER TABLE users
    ALTER COLUMN locale SET DEFAULT 'zh-CN';

ALTER TABLE users
    ALTER COLUMN locale SET NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT chk_users_locale
        CHECK (locale IN ('zh-CN', 'en-US'));
