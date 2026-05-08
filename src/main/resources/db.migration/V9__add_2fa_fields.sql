-- Add 2FA fields to users table
ALTER TABLE users
  ADD COLUMN totp_secret VARCHAR(64),
  ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN totp_backup_codes TEXT;

-- totp_secret     : Base32-encoded TOTP secret generated per user
-- totp_enabled    : Whether 2FA is active for this account
-- totp_backup_codes : JSON array of hashed one-time backup codes
