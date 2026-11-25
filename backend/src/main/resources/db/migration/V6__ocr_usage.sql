-- Daily per-user OCR upload quota tracking
CREATE TABLE ocr_usage (
    user_id    BIGINT    NOT NULL,
    usage_date DATE      NOT NULL,
    usage_count INTEGER   NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT pk_ocr_usage PRIMARY KEY (user_id, usage_date),
    CONSTRAINT fk_ocr_usage_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_ocr_usage_count CHECK (usage_count >= 0)
);
