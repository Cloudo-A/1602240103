CREATE TABLE patient_account (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    phone         VARCHAR(11)  NOT NULL,
    name          VARCHAR(20)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    DATETIME     NOT NULL,
    UNIQUE KEY uk_patient_account_phone (phone)
);

CREATE TABLE patient_auth_token (
    token_hash         CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    patient_account_id BIGINT   NOT NULL,
    expires_at         DATETIME NOT NULL,
    KEY idx_patient_token_expiry (expires_at),
    CONSTRAINT fk_patient_token_account FOREIGN KEY (patient_account_id) REFERENCES patient_account (id)
);

CREATE TABLE appointment_request (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    patient_account_id  BIGINT       NOT NULL,
    contact_name        VARCHAR(20)  NOT NULL,
    contact_phone       VARCHAR(11)  NOT NULL,
    clinic_id           BIGINT       NOT NULL,
    preferred_date      DATE         NOT NULL,
    time_slot           VARCHAR(10)  NOT NULL,
    note                VARCHAR(200) NULL,
    status              VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    created_at          DATETIME     NOT NULL,
    handled_at          DATETIME     NULL,
    handled_by_user_id  BIGINT       NULL,
    handled_by          VARCHAR(60)  NULL,
    invalid_reason      VARCHAR(200) NULL,
    withdrawn_at        DATETIME     NULL,
    appointment_id      BIGINT       NULL,
    version             INT          NOT NULL DEFAULT 0,
    KEY idx_request_account_status (patient_account_id, status),
    KEY idx_request_created (created_at),
    KEY idx_request_clinic_created (clinic_id, created_at),
    UNIQUE KEY uk_request_appointment (appointment_id),
    CONSTRAINT fk_request_account FOREIGN KEY (patient_account_id) REFERENCES patient_account (id),
    CONSTRAINT fk_request_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (id),
    CONSTRAINT fk_request_handler FOREIGN KEY (handled_by_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_request_appointment FOREIGN KEY (appointment_id) REFERENCES appointment (id),
    CONSTRAINT ck_request_status CHECK (status IN ('PENDING','CONFIRMED','INVALID','WITHDRAWN')),
    CONSTRAINT ck_request_slot CHECK (time_slot IN ('MORNING','AFTERNOON','EVENING')),
    CONSTRAINT ck_request_confirmed CHECK ((status = 'CONFIRMED') = (appointment_id IS NOT NULL)),
    CONSTRAINT ck_request_invalid CHECK ((status = 'INVALID') = (invalid_reason IS NOT NULL)),
    CONSTRAINT ck_request_withdrawn CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL)),
    CONSTRAINT ck_request_handler CHECK (
        ((status IN ('CONFIRMED','INVALID')) = (handled_at IS NOT NULL)) AND
        ((status IN ('CONFIRMED','INVALID')) = (handled_by_user_id IS NOT NULL)) AND
        ((status IN ('CONFIRMED','INVALID')) = (handled_by IS NOT NULL))
    )
);

CREATE TABLE appointment_request_item (
    request_id BIGINT NOT NULL,
    item_id    BIGINT NOT NULL,
    PRIMARY KEY (request_id, item_id),
    CONSTRAINT fk_request_item_request FOREIGN KEY (request_id) REFERENCES appointment_request (id),
    CONSTRAINT fk_request_item_treatment FOREIGN KEY (item_id) REFERENCES treatment_item (id)
);

ALTER TABLE request_record
    ADD COLUMN account_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'EMPLOYEE',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (account_type, request_id),
    ADD CONSTRAINT ck_request_account_type CHECK (account_type IN ('EMPLOYEE','PATIENT'));
