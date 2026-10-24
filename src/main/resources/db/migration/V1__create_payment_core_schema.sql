CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_email_normalized CHECK (email = lower(btrim(email)) AND email <> ''),
    CONSTRAINT users_role_valid CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT users_email_unique UNIQUE (email)
);

CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'RUB',
    balance_minor BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ledger_accounts_kind_valid CHECK (kind IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT ledger_accounts_currency_valid CHECK (currency = 'RUB'),
    CONSTRAINT ledger_accounts_customer_balance_nonnegative CHECK (kind = 'SYSTEM' OR balance_minor >= 0),
    CONSTRAINT ledger_accounts_identity_kind_currency_unique UNIQUE (id, kind, currency)
);

CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    account_id UUID NOT NULL,
    account_kind VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER',
    currency VARCHAR(3) NOT NULL DEFAULT 'RUB',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT wallets_currency_valid CHECK (currency = 'RUB'),
    CONSTRAINT wallets_account_kind_customer CHECK (account_kind = 'CUSTOMER'),
    CONSTRAINT wallets_user_currency_unique UNIQUE (user_id, currency),
    CONSTRAINT wallets_account_unique UNIQUE (account_id),
    CONSTRAINT wallets_customer_account_fk FOREIGN KEY (account_id, account_kind, currency)
        REFERENCES ledger_accounts (id, kind, currency)
);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    actor_id UUID NOT NULL REFERENCES users (id),
    source_account_id UUID NOT NULL REFERENCES ledger_accounts (id),
    target_account_id UUID NOT NULL REFERENCES ledger_accounts (id),
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'RUB',
    rejection_code VARCHAR(64),
    original_payment_id UUID REFERENCES payments (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT payments_type_valid CHECK (type IN ('TOP_UP', 'TRANSFER', 'REFUND')),
    CONSTRAINT payments_status_valid CHECK (status IN ('POSTED', 'REJECTED')),
    CONSTRAINT payments_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT payments_currency_valid CHECK (currency = 'RUB'),
    CONSTRAINT payments_rejection_consistent CHECK (
        (status = 'POSTED' AND rejection_code IS NULL)
        OR (status = 'REJECTED' AND rejection_code IS NOT NULL AND rejection_code <> '')
    ),
    CONSTRAINT payments_posted_accounts_distinct CHECK (status <> 'POSTED' OR source_account_id <> target_account_id),
    CONSTRAINT payments_refund_reference_consistent CHECK (
        (type = 'REFUND' AND original_payment_id IS NOT NULL AND original_payment_id <> id)
        OR (type <> 'REFUND' AND original_payment_id IS NULL)
    )
);

CREATE UNIQUE INDEX payments_one_posted_refund_per_original
    ON payments (original_payment_id) WHERE type = 'REFUND' AND status = 'POSTED';
CREATE INDEX payments_actor_history ON payments (actor_id, created_at DESC, id DESC);
CREATE INDEX payments_source_history ON payments (source_account_id, created_at DESC, id DESC);
CREATE INDEX payments_target_history ON payments (target_account_id, created_at DESC, id DESC);
CREATE INDEX payments_original_reference ON payments (original_payment_id) WHERE original_payment_id IS NOT NULL;

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ledger_transactions_payment_unique UNIQUE (payment_id)
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions (id),
    account_id UUID NOT NULL REFERENCES ledger_accounts (id),
    delta_minor BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ledger_entries_delta_nonzero CHECK (delta_minor <> 0),
    CONSTRAINT ledger_entries_transaction_account_unique UNIQUE (transaction_id, account_id)
);

CREATE INDEX ledger_entries_account_history ON ledger_entries (account_id, created_at DESC, id DESC);

CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users (id),
    command_type VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    payment_id UUID REFERENCES payments (id),
    response_status SMALLINT,
    response_body JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT idempotency_command_type_valid CHECK (command_type IN ('TOP_UP', 'TRANSFER', 'REFUND')),
    CONSTRAINT idempotency_key_format_valid CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT idempotency_fingerprint_valid CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_response_consistent CHECK (
        (response_status IS NULL AND response_body IS NULL AND payment_id IS NULL)
        OR (response_status IS NOT NULL AND response_status BETWEEN 200 AND 599 AND response_body IS NOT NULL
            AND jsonb_typeof(response_body) = 'object' AND payment_id IS NOT NULL)
    ),
    CONSTRAINT idempotency_actor_command_key_unique UNIQUE (actor_id, command_type, idempotency_key)
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_id UUID REFERENCES users (id),
    payment_id UUID REFERENCES payments (id),
    event_type VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT audit_events_details_object CHECK (jsonb_typeof(details) = 'object'),
    CONSTRAINT audit_events_type_nonempty CHECK (event_type <> ''),
    CONSTRAINT audit_events_outcome_nonempty CHECK (outcome <> '')
);

CREATE INDEX audit_events_actor_history ON audit_events (actor_id, created_at DESC, id DESC);
CREATE INDEX audit_events_payment_reference ON audit_events (payment_id) WHERE payment_id IS NOT NULL;

-- Cross-row monetary guarantees (balanced pairs, matching cached balances,
-- permission checks, and posting only POSTED payments) belong to the posting
-- service and later migrations. This foundation does not expose a posting API.
