-- The queue itself. Domain-agnostic: order/ (or anything else) only ever sees
-- this through JobQueueClient.
--
-- Lifecycle:
--   PENDING  -> RUNNING    claimed by a worker (FOR UPDATE SKIP LOCKED)
--   RUNNING  -> SUCCEEDED  worker completed it (fenced by locked_by)
--   RUNNING  -> PENDING    worker failed it but attempts remain (run_at pushed out for backoff),
--                          or the reaper reclaimed it after a stale heartbeat
--   RUNNING  -> FAILED     worker failed it and attempts are exhausted (terminal)
CREATE TABLE jobs (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    type             TEXT        NOT NULL,
    payload          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status           TEXT        NOT NULL DEFAULT 'PENDING',

    -- Optional caller-supplied key so retrying "enqueue" (e.g. a double-clicked
    -- checkout) can't create a second CHARGE_PAYMENT job. NULLs don't collide.
    idempotency_key  TEXT        UNIQUE,

    attempts         INT         NOT NULL DEFAULT 0,
    max_attempts     INT         NOT NULL DEFAULT 5,
    run_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    locked_by        TEXT,
    locked_at        TIMESTAMPTZ,
    heartbeat_at     TIMESTAMPTZ,

    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT jobs_status_valid
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT jobs_type_not_blank
        CHECK (length(trim(type)) > 0),
    CONSTRAINT jobs_attempts_in_range
        CHECK (attempts >= 0 AND max_attempts > 0 AND attempts <= max_attempts),
    -- A job holds a lock if and only if it is RUNNING. Catches any code path
    -- that forgets to set or clear the lock.
    CONSTRAINT jobs_lock_matches_status
        CHECK ((status = 'RUNNING') = (locked_by IS NOT NULL AND locked_at IS NOT NULL))
);

-- Claim query: WHERE status = 'PENDING' AND run_at <= now() ORDER BY run_at
-- Partial index stays small because finished jobs drop out of it.
CREATE INDEX jobs_claimable_idx ON jobs (run_at) WHERE status = 'PENDING';

-- Reaper query: WHERE status = 'RUNNING' AND heartbeat_at < now() - interval
CREATE INDEX jobs_running_heartbeat_idx ON jobs (heartbeat_at) WHERE status = 'RUNNING';

-- Keep updated_at honest even for native SQL updates (claim/complete/fail),
-- which bypass Hibernate's timestamp handling.
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER jobs_set_updated_at
    BEFORE UPDATE ON jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
