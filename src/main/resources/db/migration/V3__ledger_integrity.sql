-- A transaction may build its journal incrementally, but cannot commit a partial posting.
-- Store the full transaction ID, not xmin (which can change after freezing/wraparound).
ALTER TABLE ledger_transactions ADD COLUMN creation_xid BIGINT NOT NULL DEFAULT txid_current();

CREATE FUNCTION reject_immutable_record_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Recorded financial data is immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER payments_immutable BEFORE UPDATE OR DELETE ON payments
FOR EACH ROW EXECUTE FUNCTION reject_immutable_record_change();
CREATE TRIGGER ledger_transactions_immutable BEFORE UPDATE OR DELETE ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_record_change();
CREATE TRIGGER ledger_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION reject_immutable_record_change();
CREATE TRIGGER wallets_immutable BEFORE UPDATE OR DELETE ON wallets
FOR EACH ROW EXECUTE FUNCTION reject_immutable_record_change();

CREATE FUNCTION protect_account_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Account identity is immutable' USING ERRCODE = '23514';
    END IF;
    IF (NEW.id, NEW.kind, NEW.currency, NEW.created_at) IS DISTINCT FROM
       (OLD.id, OLD.kind, OLD.currency, OLD.created_at) THEN
        RAISE EXCEPTION 'Account identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ledger_accounts_identity_immutable BEFORE UPDATE OR DELETE ON ledger_accounts
FOR EACH ROW EXECUTE FUNCTION protect_account_identity();

CREATE FUNCTION stamp_ledger_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.creation_xid := txid_current();
    RETURN NEW;
END;
$$;
CREATE TRIGGER ledger_transactions_stamp BEFORE INSERT ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION stamp_ledger_transaction();

CREATE FUNCTION reject_late_ledger_entry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_xid BIGINT;
BEGIN
    SELECT creation_xid INTO owner_xid FROM ledger_transactions WHERE id = NEW.transaction_id;
    IF FOUND AND owner_xid <> txid_current() THEN
        RAISE EXCEPTION 'Committed journal cannot be extended' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ledger_entries_same_transaction BEFORE INSERT ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION reject_late_ledger_entry();

CREATE FUNCTION check_payment_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    payment_uuid UUID;
    p payments%ROWTYPE;
    tx_count BIGINT;
    entry_count BIGINT;
    correct_entries BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'payments' THEN
        payment_uuid := NEW.id;
    ELSIF TG_TABLE_NAME = 'ledger_transactions' THEN
        payment_uuid := NEW.payment_id;
    ELSE
        SELECT payment_id INTO payment_uuid FROM ledger_transactions WHERE id = NEW.transaction_id;
    END IF;
    SELECT * INTO p FROM payments WHERE id = payment_uuid;
    SELECT count(*) INTO tx_count FROM ledger_transactions WHERE payment_id = payment_uuid;
    IF p.status = 'REJECTED' THEN
        IF tx_count <> 0 THEN
            RAISE EXCEPTION 'Rejected payment cannot have a journal' USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;
    SELECT count(*), count(*) FILTER (WHERE
        (e.account_id = p.source_account_id AND e.delta_minor = -p.amount_minor) OR
        (e.account_id = p.target_account_id AND e.delta_minor = p.amount_minor))
    INTO entry_count, correct_entries
    FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id
    WHERE t.payment_id = payment_uuid;
    IF tx_count <> 1 OR entry_count <> 2 OR correct_entries <> 2 THEN
        RAISE EXCEPTION 'Posted payment requires its complete balanced journal' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER payments_complete_journal AFTER INSERT ON payments
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payment_journal();
CREATE CONSTRAINT TRIGGER ledger_transactions_complete_journal AFTER INSERT ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payment_journal();
CREATE CONSTRAINT TRIGGER ledger_entries_complete_journal AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payment_journal();

CREATE FUNCTION check_account_balance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    account_uuid UUID;
    actual_balance BIGINT;
    journal_balance NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'ledger_accounts' THEN
        account_uuid := NEW.id;
    ELSE
        account_uuid := NEW.account_id;
    END IF;
    SELECT balance_minor INTO actual_balance FROM ledger_accounts WHERE id = account_uuid;
    SELECT COALESCE(sum(delta_minor), 0) INTO journal_balance FROM ledger_entries WHERE account_id = account_uuid;
    IF actual_balance IS DISTINCT FROM journal_balance THEN
        RAISE EXCEPTION 'Account balance must match its journal' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ledger_accounts_balance_matches_journal AFTER INSERT OR UPDATE ON ledger_accounts
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_balance();
CREATE CONSTRAINT TRIGGER ledger_entries_balance_matches_journal AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_balance();

-- Existing data is never rewritten to manufacture consistency. Stop migration if it needs investigation.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM ledger_accounts a
        WHERE a.balance_minor <> (SELECT COALESCE(sum(e.delta_minor), 0) FROM ledger_entries e WHERE e.account_id = a.id)
    ) OR EXISTS (
        SELECT 1 FROM payments p
        WHERE (p.status = 'REJECTED' AND EXISTS (SELECT 1 FROM ledger_transactions t WHERE t.payment_id = p.id))
        OR (p.status = 'POSTED' AND (
            (SELECT count(*) FROM ledger_transactions t WHERE t.payment_id = p.id) <> 1
            OR (SELECT count(*) FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id WHERE t.payment_id = p.id) <> 2
            OR (SELECT count(*) FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id WHERE t.payment_id = p.id AND
                ((e.account_id = p.source_account_id AND e.delta_minor = -p.amount_minor) OR
                 (e.account_id = p.target_account_id AND e.delta_minor = p.amount_minor))) <> 2
        ))
    ) THEN
        RAISE EXCEPTION 'Existing financial data requires reconciliation before ledger integrity migration';
    END IF;
END;
$$;
