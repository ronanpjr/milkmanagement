-- Baseline schema for Supply & Production ERP (PostgreSQL)

CREATE TABLE users (
    id       BIGSERIAL    PRIMARY KEY,
    email    VARCHAR(255) NOT NULL,
    name     VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role     VARCHAR(255) NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE suppliers (
    id                 BIGSERIAL    PRIMARY KEY,
    name               VARCHAR(255) NOT NULL,
    route              VARCHAR(255),
    payment_handle     VARCHAR(255),
    payment_method     VARCHAR(255),
    tax_id             VARCHAR(255),
    active             BOOLEAN      NOT NULL,
    use_tiered_pricing BOOLEAN      NOT NULL
);

CREATE TABLE collection_entries (
    id          BIGSERIAL PRIMARY KEY,
    entry_date  DATE      NOT NULL,
    quantity    INTEGER   NOT NULL,
    supplier_id BIGINT    NOT NULL,
    CONSTRAINT fk_collection_entries_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);

CREATE TABLE monthly_settlements (
    id               BIGSERIAL      PRIMARY KEY,
    settlement_month INTEGER        NOT NULL,
    settlement_year  INTEGER        NOT NULL,
    start_date       DATE           NOT NULL,
    end_date         DATE           NOT NULL,
    total_volume     INTEGER        NOT NULL,
    unit_price       DECIMAL(10, 4),
    gross_total      DECIMAL(10, 2),
    deductions_total DECIMAL(10, 2),
    net_total        DECIMAL(10, 2),
    quality_metric_a DECIMAL(10, 4),
    quality_metric_b DECIMAL(10, 4),
    quality_metric_c DECIMAL(5, 2),
    quality_metric_d DECIMAL(5, 2),
    volume_bonus     DECIMAL(10, 4),
    loyalty_bonus    DECIMAL(10, 4),
    final_price      DECIMAL(10, 4),
    payment_status   VARCHAR(255),
    payment_date     DATE,
    supplier_id      BIGINT         NOT NULL,
    CONSTRAINT fk_monthly_settlements_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);

CREATE TABLE deductions (
    id            BIGSERIAL      PRIMARY KEY,
    description   VARCHAR(255)   NOT NULL,
    amount        DECIMAL(10, 2) NOT NULL,
    settlement_id BIGINT         NOT NULL,
    CONSTRAINT fk_deductions_settlement FOREIGN KEY (settlement_id) REFERENCES monthly_settlements (id)
);
