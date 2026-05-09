ALTER TABLE llm_usage_event
    ADD COLUMN business_operation_id VARCHAR(128),
    ADD COLUMN execution_disposition VARCHAR(64) NOT NULL DEFAULT 'EXECUTED';

CREATE INDEX idx_llm_usage_event_business_operation_id
    ON llm_usage_event (business_operation_id);

ALTER TABLE usage_rejection_records
    ADD COLUMN business_operation_id VARCHAR(128);

CREATE INDEX idx_usage_rejection_records_business_operation_id
    ON usage_rejection_records (business_operation_id);
