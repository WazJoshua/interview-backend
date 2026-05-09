ALTER TABLE payment_order
    ADD COLUMN payable_activated_at TIMESTAMP;

UPDATE payment_order
SET payable_activated_at = created_at
WHERE order_type IN ('SUBSCRIPTION_PURCHASE', 'CREDIT_PURCHASE')
  AND payable_activated_at IS NULL;

CREATE INDEX idx_payment_order_active_payable
    ON payment_order (user_id, created_at DESC, id DESC)
    WHERE status IN ('CREATED', 'PENDING_PROVIDER', 'AWAITING_CONFIRMATION')
      AND payable_activated_at IS NOT NULL;

CREATE INDEX idx_payment_order_expirable
    ON payment_order (expires_at ASC, id ASC)
    WHERE status IN ('CREATED', 'PENDING_PROVIDER')
      AND payable_activated_at IS NOT NULL;
