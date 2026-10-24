-- A previously committed reservation cannot be retroactively completed safely.
-- Stop rather than silently rewriting unknown historical data.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM idempotency_records r
        LEFT JOIN payments p ON p.id = r.payment_id
        WHERE r.response_status IS NULL OR r.response_body IS NULL OR r.payment_id IS NULL
           OR p.id IS NULL OR p.actor_id <> r.actor_id OR p.type <> r.command_type
           OR (p.status = 'POSTED' AND r.response_status <> 201)
           OR (p.status = 'REJECTED' AND r.response_status <> 409)
    ) THEN
        RAISE EXCEPTION 'Existing idempotency records require review before result-integrity migration';
    END IF;
END;
$$;

CREATE FUNCTION protect_idempotency_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Idempotency records cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.response_status IS NOT NULL THEN
        RAISE EXCEPTION 'Completed idempotency result is immutable' USING ERRCODE = '23514';
    END IF;
    IF (NEW.id, NEW.actor_id, NEW.command_type, NEW.idempotency_key,
        NEW.request_fingerprint, NEW.created_at) IS DISTINCT FROM
       (OLD.id, OLD.actor_id, OLD.command_type, OLD.idempotency_key,
        OLD.request_fingerprint, OLD.created_at) THEN
        RAISE EXCEPTION 'Idempotency reservation identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER idempotency_record_immutable
BEFORE UPDATE OR DELETE ON idempotency_records
FOR EACH ROW EXECUTE FUNCTION protect_idempotency_record();

-- An INSERT can reserve a key while the operation is running, but no incomplete
-- reservation may commit. Deferred triggers inspect the final row, not the event's
-- initial NEW value, so INSERT followed by UPDATE in one transaction is allowed.
CREATE FUNCTION require_completed_idempotency_result() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    r idempotency_records%ROWTYPE;
    p payments%ROWTYPE;
BEGIN
    SELECT * INTO r FROM idempotency_records WHERE id = NEW.id;
    IF NOT FOUND OR r.response_status IS NULL OR r.response_body IS NULL OR r.payment_id IS NULL THEN
        RAISE EXCEPTION 'Idempotency reservation requires a completed result at commit'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO p FROM payments WHERE id = r.payment_id;
    IF NOT FOUND OR p.actor_id <> r.actor_id OR p.type <> r.command_type
       OR (p.status = 'POSTED' AND r.response_status <> 201)
       OR (p.status = 'REJECTED' AND r.response_status <> 409) THEN
        RAISE EXCEPTION 'Idempotency result does not match its payment'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER idempotency_complete_at_commit
AFTER INSERT OR UPDATE ON idempotency_records
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_completed_idempotency_result();

-- This fixed technical account belongs to the local TOP_UP simulator. Other
-- SYSTEM accounts can exist for independent fixtures and future integrations.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM ledger_accounts
        WHERE id = 'a880a12b-e647-483f-9433-98c2c85ff36b'::uuid
    ) THEN
        RAISE EXCEPTION 'Simulator account ID is already used';
    END IF;
    INSERT INTO ledger_accounts (id, kind, currency, balance_minor)
    VALUES ('a880a12b-e647-483f-9433-98c2c85ff36b', 'SYSTEM', 'RUB', 0);
END;
$$;
