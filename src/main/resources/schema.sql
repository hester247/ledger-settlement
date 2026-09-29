CREATE TABLE IF NOT EXISTS payments (
                                        id VARCHAR(255) PRIMARY KEY,
    merchant_id VARCHAR(255) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(10) NOT NULL,
    recorded_at TIMESTAMP NOT NULL
    );