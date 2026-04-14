CREATE TABLE users (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_id             UUID         NOT NULL REFERENCES agencies(id),
    email                 VARCHAR(255) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    first_name            VARCHAR(100) NOT NULL,
    last_name             VARCHAR(100) NOT NULL,
    role                  VARCHAR(50)  NOT NULL,
    language              VARCHAR(5)   NOT NULL DEFAULT 'PL',
    assigned_property_ids UUID[]       NOT NULL DEFAULT '{}',
    status                VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_email_agency UNIQUE (agency_id, email)
);

CREATE INDEX idx_users_agency_id ON users (agency_id);
CREATE INDEX idx_users_role ON users (agency_id, role);
