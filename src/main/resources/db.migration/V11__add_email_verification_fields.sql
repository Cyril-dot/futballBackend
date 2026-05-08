-- Add email verification and password reset fields to users table
ALTER TABLE users
  ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN email_verified_at TIMESTAMPTZ,
  ADD COLUMN verification_token VARCHAR(255),
  ADD COLUMN reset_token VARCHAR(255),
  ADD COLUMN reset_token_expires_at TIMESTAMPTZ;

-- email_verified: Whether the user's email has been verified
-- email_verified_at: When the email was verified
-- verification_token: Token for email verification link
-- reset_token: Token for password reset link
-- reset_token_expires_at: Expiry time for password reset token
