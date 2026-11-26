ALTER TABLE settlement
    ADD COLUMN patient_account_no VARCHAR(30) NULL,
    ADD COLUMN patient_name VARCHAR(60) NULL,
    ADD COLUMN clinic_name VARCHAR(60) NULL,
    ADD COLUMN dentist_name VARCHAR(60) NULL;

UPDATE settlement s
JOIN visit v ON v.id = s.visit_id
JOIN appointment a ON a.id = v.appointment_id
JOIN patient p ON p.id = a.patient_id
JOIN clinic c ON c.id = v.clinic_id
JOIN dentist d ON d.id = v.dentist_id
SET s.patient_account_no = p.account_no, s.patient_name = p.name,
    s.clinic_name = c.name, s.dentist_name = d.name;

ALTER TABLE settlement
    MODIFY patient_account_no VARCHAR(30) NOT NULL,
    MODIFY patient_name VARCHAR(60) NOT NULL,
    MODIFY clinic_name VARCHAR(60) NOT NULL,
    MODIFY dentist_name VARCHAR(60) NOT NULL;

CREATE TABLE settlement_sequence (
    issue_date DATE NOT NULL PRIMARY KEY,
    last_number SMALLINT UNSIGNED NOT NULL,
    CONSTRAINT ck_settlement_sequence CHECK (last_number BETWEEN 0 AND 9999)
);

INSERT INTO settlement_sequence (issue_date,last_number)
SELECT DATE(issued_at), MAX(CAST(RIGHT(settlement_no,4) AS UNSIGNED))
FROM settlement
WHERE REGEXP_LIKE(settlement_no, '^JS[0-9]{8}-[0-9]{4}$')
  AND SUBSTRING(settlement_no,3,8) = DATE_FORMAT(issued_at,'%Y%m%d')
GROUP BY DATE(issued_at);
