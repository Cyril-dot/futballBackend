-- Create withdrawal_requests table
CREATE TABLE withdrawal_requests (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    amount          NUMERIC(19,4) NOT NULL,
    currency        VARCHAR(10) NOT NULL DEFAULT 'GHS',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    method          VARCHAR(50) NOT NULL DEFAULT 'mobile_money',
    account_number  VARCHAR(100),
    account_name    VARCHAR(150),
    network         VARCHAR(50),
    admin_id        UUID REFERENCES users(id),
    admin_note      TEXT,
    super_admin_id  UUID REFERENCES users(id),
    super_admin_note TEXT,
    reviewed_at     TIMESTAMPTZ,
    settled_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- status values: PENDING | APPROVED | REJECTED | SETTLED | FAILED
-- admin_id       : Admin who approved/rejected
-- super_admin_id : Super admin who settled/marked paid
-- reviewed_at    : When admin took action
-- settled_at     : When super admin settled it

CREATE INDEX idx_withdrawal_user    ON withdrawal_requests(user_id);
CREATE INDEX idx_withdrawal_status  ON withdrawal_requests(status);
CREATE INDEX idx_withdrawal_admin   ON withdrawal_requests(admin_id);
