ALTER TABLE llm_model_catalog
    ADD COLUMN provider_id BIGINT;

ALTER TABLE llm_model_catalog
    ADD CONSTRAINT fk_llm_model_catalog_provider
        FOREIGN KEY (provider_id) REFERENCES llm_provider (id);

CREATE INDEX idx_llm_model_catalog_provider_id
    ON llm_model_catalog (provider_id);

ALTER TABLE llm_model_pricing_version
    ADD COLUMN model_id BIGINT;

ALTER TABLE llm_model_pricing_version
    ADD CONSTRAINT fk_llm_model_pricing_version_model
        FOREIGN KEY (model_id) REFERENCES llm_model_catalog (id);

CREATE INDEX idx_llm_model_pricing_version_model_id
    ON llm_model_pricing_version (model_id);

ALTER TABLE llm_usage_event
    ADD COLUMN provider_id BIGINT,
    ADD COLUMN model_id BIGINT;

ALTER TABLE llm_usage_event
    ADD CONSTRAINT fk_llm_usage_event_provider
        FOREIGN KEY (provider_id) REFERENCES llm_provider (id);

ALTER TABLE llm_usage_event
    ADD CONSTRAINT fk_llm_usage_event_model
        FOREIGN KEY (model_id) REFERENCES llm_model_catalog (id);

CREATE INDEX idx_llm_usage_event_provider_id_created_at
    ON llm_usage_event (provider_id, created_at DESC, id DESC);

CREATE INDEX idx_llm_usage_event_model_id_created_at
    ON llm_usage_event (model_id, created_at DESC, id DESC);
