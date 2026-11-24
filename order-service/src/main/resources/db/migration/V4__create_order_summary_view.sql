CREATE TABLE order_summary_view (
    order_id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    restaurant_id VARCHAR(64) NOT NULL,
    restaurant_name VARCHAR(150),
    status VARCHAR(30) NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    item_count INT NOT NULL DEFAULT 0,
    items_json TEXT NOT NULL,
    cancellation_reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_order_summary_customer_created
    ON order_summary_view(customer_id, created_at DESC);