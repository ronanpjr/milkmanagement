-- Refresh token rotation store (SHA-256 hashes only)
CREATE TABLE refresh_tokens (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    expires_at TIMESTAMP   NOT NULL,
    revoked_at TIMESTAMP,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
