CREATE TABLE IF NOT EXISTS aggregates (
    aggregate_id VARCHAR(255) PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    current_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS events (
    event_id VARCHAR(64) PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    partition_id INTEGER NOT NULL,
    sequence_number BIGINT NOT NULL,
    partition_sequence_number BIGINT NOT NULL,
    global_sequence BIGSERIAL,
    vector_clock INTEGER[] NOT NULL,
    causal_dependencies VARCHAR(64)[] NOT NULL DEFAULT '{}',
    tags VARCHAR(255)[] DEFAULT '{}',
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (aggregate_id, sequence_number),
    UNIQUE (partition_id, partition_sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_events_aggregate ON events(aggregate_id, sequence_number);
CREATE INDEX IF NOT EXISTS idx_events_partition ON events(partition_id, partition_sequence_number);
CREATE INDEX IF NOT EXISTS idx_events_type ON events(event_type);
CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events(timestamp);
CREATE INDEX IF NOT EXISTS idx_events_global_seq ON events(global_sequence);
CREATE INDEX IF NOT EXISTS idx_events_tags ON events USING GIN(tags);

CREATE TABLE IF NOT EXISTS snapshots (
    snapshot_id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    snapshot_state JSONB NOT NULL,
    last_sequence BIGINT NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (aggregate_id) REFERENCES aggregates(aggregate_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_snapshots_aggregate ON snapshots(aggregate_id, last_sequence DESC);

CREATE TABLE IF NOT EXISTS consumer_cursors (
    consumer_id VARCHAR(255) PRIMARY KEY,
    cursor_vector INTEGER[] NOT NULL,
    last_event_id VARCHAR(64),
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS subscriptions (
    subscription_id VARCHAR(255) PRIMARY KEY,
    event_pattern VARCHAR(255) NOT NULL,
    consumer_id VARCHAR(255) NOT NULL,
    cursor_vector INTEGER[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_push_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS projections (
    projection_id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    event_type_pattern VARCHAR(255) NOT NULL,
    handler_logic TEXT NOT NULL,
    target_table VARCHAR(255),
    processed_vector INTEGER[] NOT NULL DEFAULT '{}',
    last_processed_event_id VARCHAR(64),
    processed_count BIGINT NOT NULL DEFAULT 0,
    last_processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING'
);

CREATE TABLE IF NOT EXISTS conflicts (
    conflict_id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    event_a_id VARCHAR(64) NOT NULL,
    event_b_id VARCHAR(64) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolution VARCHAR(32),
    resolution_notes TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN'
);

CREATE INDEX IF NOT EXISTS idx_conflicts_aggregate ON conflicts(aggregate_id);
CREATE INDEX IF NOT EXISTS idx_conflicts_status ON conflicts(status);

CREATE TABLE IF NOT EXISTS replication_status (
    partition_id INTEGER NOT NULL,
    node_id VARCHAR(255) NOT NULL,
    last_applied_sequence BIGINT NOT NULL DEFAULT 0,
    last_applied_event_id VARCHAR(64),
    last_heartbeat TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lag_seconds BIGINT DEFAULT 0,
    PRIMARY KEY (partition_id, node_id)
);

CREATE TABLE IF NOT EXISTS cluster_nodes (
    node_id VARCHAR(255) PRIMARY KEY,
    node_role VARCHAR(32) NOT NULL,
    host VARCHAR(255) NOT NULL,
    grpc_port INTEGER NOT NULL,
    http_port INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UP',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_heartbeat TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS partition_sequence (
    partition_id INTEGER PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0
);

INSERT INTO partition_sequence (partition_id, last_sequence)
SELECT s, 0 FROM generate_series(0, 7) s
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION notify_event_insert() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('events_channel', 
        json_build_object(
            'event_id', NEW.event_id,
            'aggregate_id', NEW.aggregate_id,
            'event_type', NEW.event_type,
            'partition_id', NEW.partition_id,
            'sequence_number', NEW.sequence_number,
            'timestamp', NEW.timestamp
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_events_notify ON events;
CREATE TRIGGER trg_events_notify
AFTER INSERT ON events
FOR EACH ROW EXECUTE FUNCTION notify_event_insert();
