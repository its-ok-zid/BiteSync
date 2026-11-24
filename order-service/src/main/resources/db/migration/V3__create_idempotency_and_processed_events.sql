CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    customer_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    order_id UUID NOT NULL,
    http_status INT NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_idempotency_key_customer UNIQUE (idempotency_key, customer_id)
);

CREATE TABLE processed_events (
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(80) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, consumer_group)
);