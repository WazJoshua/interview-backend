ALTER TABLE payment_order
    DROP CONSTRAINT IF EXISTS uq_payment_order_idempotency_key;

ALTER TABLE payment_order
    DROP CONSTRAINT IF EXISTS uq_payment_order_user_idempotency_key;

ALTER TABLE payment_order
    ADD CONSTRAINT uq_payment_order_user_idempotency_key UNIQUE (user_id, idempotency_key);
