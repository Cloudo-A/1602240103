ALTER TABLE request_record
    MODIFY request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD COLUMN response_status SMALLINT NULL,
    ADD COLUMN response_type VARCHAR(120) NULL,
    ADD COLUMN response_body MEDIUMBLOB NULL;

CREATE TABLE patient_number (
    singleton TINYINT NOT NULL PRIMARY KEY,
    last_number INT NOT NULL,
    CONSTRAINT ck_patient_number_singleton CHECK (singleton = 1),
    CONSTRAINT ck_patient_number_range CHECK (last_number BETWEEN 0 AND 999999)
);

INSERT INTO patient_number (singleton, last_number)
SELECT 1, COALESCE(MAX(CAST(SUBSTRING(account_no,2) AS UNSIGNED)),0)
FROM patient WHERE account_no REGEXP '^P[0-9]{6}$';
