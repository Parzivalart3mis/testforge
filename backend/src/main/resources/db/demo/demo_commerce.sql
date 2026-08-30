-- ---------------------------------------------------------------------------
-- Demo target schema: a 22-table commerce domain.
--
-- This is not a TestForge table. It is a stand-in for the kind of schema an
-- engineer points TestForge at, and it is deliberately awkward in the ways real
-- schemas are, so the platform is exercised against the cases that actually
-- break naive generators:
--
--   * a genuine foreign-key cycle - customer.primary_order_id <-> order_header
--     - broken only by the one nullable edge
--   * three self-references at different depths: category.parent_id,
--     employee.manager_id, customer.referred_by_id
--   * junction tables whose entire primary key is foreign keys (inventory,
--     price_list_entry, shipment_item)
--   * a composite foreign key (shipment_item -> order_line)
--   * both spellings of a generated key: serial and GENERATED ALWAYS AS IDENTITY
--   * a STORED generated column the seeder must not try to write
--   * enums, arrays, jsonb, inet, uuid, numeric with scale, citext-like columns
--   * PII of most classes, so masking has something to do
-- ---------------------------------------------------------------------------

CREATE TYPE order_status AS ENUM ('DRAFT', 'PLACED', 'PAID', 'FULFILLED', 'CANCELLED', 'REFUNDED');
CREATE TYPE payment_status AS ENUM ('PENDING', 'AUTHORISED', 'CAPTURED', 'FAILED', 'REFUNDED');
CREATE TYPE shipment_status AS ENUM ('PREPARING', 'IN_TRANSIT', 'DELIVERED', 'LOST', 'RETURNED');
CREATE TYPE contact_kind AS ENUM ('BILLING', 'TECHNICAL', 'SUPPORT', 'MARKETING');


-- 1 -------------------------------------------------------------------------
CREATE TABLE country (
    id           SERIAL PRIMARY KEY,
    iso_code     CHAR(2)      NOT NULL UNIQUE,
    country      VARCHAR(80)  NOT NULL,
    currency     CHAR(3)      NOT NULL,
    calling_code VARCHAR(6),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 2 -------------------------------------------------------------------------
CREATE TABLE region (
    id         SERIAL PRIMARY KEY,
    country_id INTEGER      NOT NULL REFERENCES country (id),
    region     VARCHAR(120) NOT NULL,
    region_code VARCHAR(10),
    UNIQUE (country_id, region)
);

-- 3 -------------------------------------------------------------------------
CREATE TABLE address (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    region_id     INTEGER      NOT NULL REFERENCES region (id),
    street_address VARCHAR(180) NOT NULL,
    address_line2 VARCHAR(180),
    city          VARCHAR(120) NOT NULL,
    postal_code   VARCHAR(16)  NOT NULL,
    latitude      NUMERIC(9, 6),
    longitude     NUMERIC(9, 6),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 4 -------------------------------------------------------------------------
-- primary_order_id closes a cycle with order_header. It is nullable, which is
-- the only reason the schema is loadable at all.
CREATE TABLE customer (
    id               SERIAL PRIMARY KEY,
    public_ref       UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    first_name       VARCHAR(80)  NOT NULL,
    last_name        VARCHAR(80)  NOT NULL,
    email            VARCHAR(255) NOT NULL UNIQUE,
    phone            VARCHAR(32),
    date_of_birth    DATE,
    national_id      VARCHAR(20),
    billing_address_id BIGINT     REFERENCES address (id),
    referred_by_id   INTEGER      REFERENCES customer (id),
    primary_order_id BIGINT,
    marketing_opt_in BOOLEAN      NOT NULL DEFAULT false,
    lifetime_value   NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tags             TEXT[],
    preferences      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    last_login_ip    INET,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_customer_lifetime_value CHECK (lifetime_value >= 0)
);

-- 5 -------------------------------------------------------------------------
CREATE TABLE customer_contact (
    id          SERIAL PRIMARY KEY,
    customer_id INTEGER      NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    kind        contact_kind NOT NULL,
    full_name   VARCHAR(160) NOT NULL,
    email       VARCHAR(255) NOT NULL,
    phone       VARCHAR(32),
    job_title   VARCHAR(120),
    is_primary  BOOLEAN      NOT NULL DEFAULT false
);

-- 6 -------------------------------------------------------------------------
CREATE TABLE employee (
    id             SERIAL PRIMARY KEY,
    manager_id     INTEGER      REFERENCES employee (id),
    address_id     BIGINT       REFERENCES address (id),
    first_name     VARCHAR(80)  NOT NULL,
    last_name      VARCHAR(80)  NOT NULL,
    -- A STORED generated column: the seeder must leave it out of the INSERT.
    full_name      VARCHAR(161) GENERATED ALWAYS AS (first_name || ' ' || last_name) STORED,
    work_email     VARCHAR(255) NOT NULL UNIQUE,
    department     VARCHAR(80)  NOT NULL,
    job_title      VARCHAR(120) NOT NULL,
    hired_on       DATE         NOT NULL,
    salary         NUMERIC(10, 2),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 7 -------------------------------------------------------------------------
CREATE TABLE supplier (
    id         SERIAL PRIMARY KEY,
    address_id BIGINT       REFERENCES address (id),
    company    VARCHAR(160) NOT NULL,
    contact_email VARCHAR(255) NOT NULL,
    contact_phone VARCHAR(32),
    website    VARCHAR(255),
    iban       VARCHAR(34),
    active     BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 8 -------------------------------------------------------------------------
CREATE TABLE category (
    id        SERIAL PRIMARY KEY,
    parent_id INTEGER      REFERENCES category (id),
    title     VARCHAR(120) NOT NULL,
    slug      VARCHAR(140) NOT NULL UNIQUE,
    description TEXT
);

-- 9 -------------------------------------------------------------------------
CREATE TABLE product (
    id          SERIAL PRIMARY KEY,
    category_id INTEGER      NOT NULL REFERENCES category (id),
    supplier_id INTEGER      NOT NULL REFERENCES supplier (id),
    product_name VARCHAR(180) NOT NULL,
    slug        VARCHAR(200) NOT NULL UNIQUE,
    description TEXT,
    attributes  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    discontinued BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 10 ------------------------------------------------------------------------
CREATE TABLE product_variant (
    id         SERIAL PRIMARY KEY,
    product_id INTEGER      NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    sku        VARCHAR(40)  NOT NULL UNIQUE,
    variant_name VARCHAR(120) NOT NULL,
    weight_grams INTEGER,
    unit_price NUMERIC(10, 2) NOT NULL,
    currency   CHAR(3)      NOT NULL DEFAULT 'USD',
    CONSTRAINT ck_variant_price CHECK (unit_price >= 0)
);

-- 11 ------------------------------------------------------------------------
CREATE TABLE warehouse (
    id         SERIAL PRIMARY KEY,
    address_id BIGINT       NOT NULL REFERENCES address (id),
    warehouse_code VARCHAR(12) NOT NULL UNIQUE,
    title      VARCHAR(120) NOT NULL,
    capacity   INTEGER      NOT NULL DEFAULT 0
);

-- 12 ------------------------------------------------------------------------
-- Whole primary key is foreign keys.
CREATE TABLE inventory (
    warehouse_id       INTEGER NOT NULL REFERENCES warehouse (id),
    product_variant_id INTEGER NOT NULL REFERENCES product_variant (id),
    quantity           INTEGER NOT NULL DEFAULT 0,
    reserved           INTEGER NOT NULL DEFAULT 0,
    reorder_level      INTEGER NOT NULL DEFAULT 10,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (warehouse_id, product_variant_id),
    CONSTRAINT ck_inventory_quantity CHECK (quantity >= 0)
);

-- 13 ------------------------------------------------------------------------
CREATE TABLE price_list (
    id         SERIAL PRIMARY KEY,
    country_id INTEGER      NOT NULL REFERENCES country (id),
    title      VARCHAR(120) NOT NULL,
    currency   CHAR(3)      NOT NULL,
    valid_from DATE         NOT NULL,
    valid_to   DATE
);

-- 14 ------------------------------------------------------------------------
CREATE TABLE price_list_entry (
    price_list_id      INTEGER NOT NULL REFERENCES price_list (id) ON DELETE CASCADE,
    product_variant_id INTEGER NOT NULL REFERENCES product_variant (id),
    price              NUMERIC(10, 2) NOT NULL,
    discount_percent   NUMERIC(5, 2) NOT NULL DEFAULT 0,
    PRIMARY KEY (price_list_id, product_variant_id)
);

-- 15 ------------------------------------------------------------------------
CREATE TABLE promotion (
    id         SERIAL PRIMARY KEY,
    promo_code VARCHAR(24)  NOT NULL UNIQUE,
    title      VARCHAR(140) NOT NULL,
    discount_percent NUMERIC(5, 2) NOT NULL,
    starts_at  TIMESTAMPTZ  NOT NULL,
    ends_at    TIMESTAMPTZ,
    max_uses   INTEGER
);

-- 16 ------------------------------------------------------------------------
CREATE TABLE order_header (
    id                  BIGSERIAL PRIMARY KEY,
    customer_id         INTEGER      NOT NULL REFERENCES customer (id),
    billing_address_id  BIGINT       NOT NULL REFERENCES address (id),
    shipping_address_id BIGINT       REFERENCES address (id),
    promotion_id        INTEGER      REFERENCES promotion (id),
    order_number        VARCHAR(24)  NOT NULL UNIQUE,
    status              order_status NOT NULL DEFAULT 'DRAFT',
    subtotal            NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tax_amount          NUMERIC(12, 2) NOT NULL DEFAULT 0,
    grand_total         NUMERIC(12, 2) NOT NULL DEFAULT 0,
    currency            CHAR(3)      NOT NULL DEFAULT 'USD',
    placed_at           TIMESTAMPTZ,
    notes               TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Closes the cycle. Added after order_header exists, which is exactly the
-- problem TestForge's cycle breaking solves for rows.
ALTER TABLE customer
    ADD CONSTRAINT fk_customer_primary_order
        FOREIGN KEY (primary_order_id) REFERENCES order_header (id);

-- 17 ------------------------------------------------------------------------
CREATE TABLE order_line (
    id                 BIGSERIAL PRIMARY KEY,
    order_id           BIGINT  NOT NULL REFERENCES order_header (id) ON DELETE CASCADE,
    product_variant_id INTEGER NOT NULL REFERENCES product_variant (id),
    line_number        INTEGER NOT NULL,
    quantity           INTEGER NOT NULL,
    unit_price         NUMERIC(10, 2) NOT NULL,
    line_total         NUMERIC(12, 2) NOT NULL,
    UNIQUE (order_id, line_number),
    CONSTRAINT ck_order_line_quantity CHECK (quantity > 0)
);

-- 18 ------------------------------------------------------------------------
CREATE TABLE payment (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT         NOT NULL REFERENCES order_header (id),
    status         payment_status NOT NULL DEFAULT 'PENDING',
    amount         NUMERIC(12, 2) NOT NULL,
    currency       CHAR(3)        NOT NULL DEFAULT 'USD',
    card_number    VARCHAR(19),
    cardholder_name VARCHAR(160),
    processor_ref  VARCHAR(64)    NOT NULL UNIQUE,
    client_ip      INET,
    captured_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- 19 ------------------------------------------------------------------------
CREATE TABLE shipment (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT          NOT NULL REFERENCES order_header (id),
    warehouse_id INTEGER         NOT NULL REFERENCES warehouse (id),
    status       shipment_status NOT NULL DEFAULT 'PREPARING',
    tracking_ref VARCHAR(48)     UNIQUE,
    carrier      VARCHAR(80),
    shipped_at   TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    weight_grams INTEGER
);

-- 20 ------------------------------------------------------------------------
-- A composite foreign key: (order_id, line_number) -> order_line's unique key.
CREATE TABLE shipment_item (
    shipment_id BIGINT  NOT NULL REFERENCES shipment (id) ON DELETE CASCADE,
    order_id    BIGINT  NOT NULL,
    line_number INTEGER NOT NULL,
    quantity    INTEGER NOT NULL,
    PRIMARY KEY (shipment_id, order_id, line_number),
    CONSTRAINT fk_shipment_item_line
        FOREIGN KEY (order_id, line_number) REFERENCES order_line (order_id, line_number)
);

-- 21 ------------------------------------------------------------------------
CREATE TABLE review (
    id          BIGSERIAL PRIMARY KEY,
    product_id  INTEGER      NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    customer_id INTEGER      NOT NULL REFERENCES customer (id),
    rating      SMALLINT     NOT NULL,
    title       VARCHAR(160),
    body        TEXT,
    helpful_votes INTEGER    NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (product_id, customer_id),
    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5)
);

-- 22 ------------------------------------------------------------------------
CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id INTEGER      REFERENCES employee (id),
    customer_id INTEGER      REFERENCES customer (id),
    action      VARCHAR(80)  NOT NULL,
    entity_type VARCHAR(80)  NOT NULL,
    entity_id   VARCHAR(64)  NOT NULL,
    actor_email VARCHAR(255),
    client_ip   INET,
    user_agent  TEXT,
    detail      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX ix_order_header_customer ON order_header (customer_id);
CREATE INDEX ix_order_line_order ON order_line (order_id);

COMMENT ON TABLE customer IS 'End customers. Holds PII and closes a foreign-key cycle with order_header.';
COMMENT ON COLUMN customer.primary_order_id IS 'The cycle-closing edge; nullable so the schema is loadable.';
COMMENT ON TABLE inventory IS 'Junction table whose entire primary key is foreign keys.';
