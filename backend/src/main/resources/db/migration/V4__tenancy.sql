-- Multi-tenant organization isolation
CREATE TABLE organizations (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_organizations_name UNIQUE (name)
);

INSERT INTO organizations (name)
SELECT 'Acme Supply Co.'
WHERE NOT EXISTS (SELECT 1 FROM organizations WHERE name = 'Acme Supply Co.');

ALTER TABLE users ADD COLUMN organization_id BIGINT;
ALTER TABLE suppliers ADD COLUMN organization_id BIGINT;

UPDATE users SET organization_id = (SELECT id FROM organizations WHERE name = 'Acme Supply Co.')
WHERE organization_id IS NULL;

UPDATE suppliers SET organization_id = (SELECT id FROM organizations WHERE name = 'Acme Supply Co.')
WHERE organization_id IS NULL;

ALTER TABLE users ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE suppliers ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE users ADD CONSTRAINT fk_users_organization
    FOREIGN KEY (organization_id) REFERENCES organizations (id);

ALTER TABLE suppliers ADD CONSTRAINT fk_suppliers_organization
    FOREIGN KEY (organization_id) REFERENCES organizations (id);

CREATE INDEX idx_suppliers_organization ON suppliers (organization_id);
CREATE INDEX idx_users_organization ON users (organization_id);
