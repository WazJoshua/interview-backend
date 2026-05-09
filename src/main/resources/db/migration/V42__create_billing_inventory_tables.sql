-- Add inventory control enabled timestamp to billing_plan_version
ALTER TABLE billing_plan_version
    ADD COLUMN inventory_control_enabled_at TIMESTAMP;

COMMENT ON COLUMN billing_plan_version.inventory_control_enabled_at IS
    'UTC timestamp when inventory control becomes effective. Orders created before this time are legacy-compatible.';

-- Create billing plan inventory master table
CREATE TABLE billing_plan_inventory
(
    id                      BIGSERIAL PRIMARY KEY,
    external_id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    billing_plan_version_id BIGINT      NOT NULL REFERENCES billing_plan_version (id),
    total_capacity          BIGINT      NOT NULL,
    reserved_count          BIGINT      NOT NULL DEFAULT 0,
    confirmed_count         BIGINT      NOT NULL DEFAULT 0,
    metadata                JSONB,
    created_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_billing_plan_inventory_external_id UNIQUE (external_id),
    CONSTRAINT uq_billing_plan_inventory_version UNIQUE (billing_plan_version_id),
    CONSTRAINT chk_billing_plan_inventory_counts_non_negative CHECK (
        reserved_count >= 0 AND confirmed_count >= 0
    ),
    CONSTRAINT chk_billing_plan_inventory_capacity_positive CHECK (
        total_capacity >= 0
    )
);

COMMENT ON TABLE billing_plan_inventory IS
    'Master inventory record for a billing plan version. Tracks total capacity and reservation/confirmation counts.';

COMMENT ON COLUMN billing_plan_inventory.total_capacity IS
    'Total saleable capacity for this plan version.';

COMMENT ON COLUMN billing_plan_inventory.reserved_count IS
    'Count of currently reserved (unconfirmed) inventory.';

COMMENT ON COLUMN billing_plan_inventory.confirmed_count IS
    'Count of confirmed (sold) inventory.';

-- Create inventory reservation record table
CREATE TABLE billing_inventory_reservation
(
    id                      BIGSERIAL PRIMARY KEY,
    external_id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    payment_order_id        BIGINT       NOT NULL REFERENCES payment_order (id),
    billing_plan_version_id BIGINT       NOT NULL REFERENCES billing_plan_version (id),
    status                  VARCHAR(32)  NOT NULL,
    metadata                JSONB,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_billing_inventory_reservation_external_id UNIQUE (external_id),
    CONSTRAINT chk_billing_inventory_reservation_status CHECK (
        status IN ('RESERVED', 'CONFIRMED', 'RELEASED')
    )
);

COMMENT ON TABLE billing_inventory_reservation IS
    'Reservation record linking a payment order to inventory. State machine: RESERVED -> CONFIRMED | RELEASED.';

COMMENT ON COLUMN billing_inventory_reservation.status IS
    'Reservation status: RESERVED (pending), CONFIRMED (sold), RELEASED (returned).';

-- Unique index: one active reservation per payment order
CREATE UNIQUE INDEX idx_billing_inventory_reservation_active_per_order
    ON billing_inventory_reservation (payment_order_id)
    WHERE status = 'RESERVED';

-- Index for reservation lookup by plan version and status
CREATE INDEX idx_billing_inventory_reservation_version_status
    ON billing_inventory_reservation (billing_plan_version_id, status, created_at DESC);

-- Index for orphan reservation recovery (RESERVED status ordered by creation time)
CREATE INDEX idx_billing_inventory_reservation_orphan_scan
    ON billing_inventory_reservation (status, created_at ASC, id ASC)
    WHERE status = 'RESERVED';