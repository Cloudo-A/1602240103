-- The mock fixture numbers refer to the V3/V5 records, not to auto-increment counters.
CREATE TEMPORARY TABLE demo_schedule (
    scheduled_at DATETIME PRIMARY KEY,
    patient_no INT NOT NULL,
    dentist_no INT NOT NULL,
    clinic_no INT NOT NULL,
    intents JSON NOT NULL,
    outcome CHAR(1) NOT NULL,
    actual_lines JSON NOT NULL,
    reason VARCHAR(255)
);
CREATE TEMPORARY TABLE demo_item_id (ref INT PRIMARY KEY, id BIGINT NOT NULL);
CREATE TEMPORARY TABLE demo_appointment_id (scheduled_at DATETIME PRIMARY KEY, id BIGINT NOT NULL);

START TRANSACTION;
INSERT INTO demo_item_id (ref, id)
SELECT ref, (SELECT id FROM treatment_item WHERE name = names.item_name)
FROM JSON_TABLE('["口腔检查","洁牙","树脂补牙","根管治疗","拔牙","局部麻醉","正畸复诊调整"]',
    '$[*]' COLUMNS(ref FOR ORDINALITY, item_name VARCHAR(100) PATH '$')) AS names;

-- Historical appointment, intended items, outcome, actual [item, quantity] and reason.
INSERT INTO demo_schedule VALUES
('2026-07-03 09:00', 1,1,1, '[1,2]', 'S', '[[1,1],[2,1]]', NULL),
('2026-07-03 10:00', 3,1,1, '[4]',   'S', '[[1,1],[4,2],[6,1.5]]', NULL),
('2026-07-08 14:00', 2,2,2, '[2]',   'C', '[]', NULL),
('2026-07-10 09:30', 5,2,2, '[3]',   'S', '[[3,2]]', NULL),
('2026-07-15 11:00', 7,3,3, '[1]',   'S', '[[1,1]]', NULL),
('2026-07-22 15:00', 4,4,3, '[1,5]', 'P', '[]', NULL),
('2026-07-29 10:00', 6,1,1, '[5]',   'S', '[[5,1],[6,2]]', NULL),
('2026-08-05 09:00', 8,2,2, '[1]',   'C', '[]', NULL),
('2026-08-12 14:30', 1,2,2, '[3]',   'S', '[[3,1]]', NULL),
('2026-08-20 15:00', 5,3,3, '[7]',   'S', '[[7,1]]', NULL),
('2026-08-26 10:00', 3,4,3, '[1]',   'N', '[]', '患者血压偏高，建议控制后再来'),
('2026-09-02 09:00', 2,1,1, '[2]',   'C', '[]', NULL),
('2026-09-09 11:30', 4,2,2, '[3,6]', 'S', '[[3,1],[6,1]]', NULL),
('2026-09-12 10:00', 1,1,1, '[1]',   'S', '[[1,1]]', NULL),
('2026-09-18 16:00', 6,4,3, '[1]',   'P', '[]', NULL),
('2026-09-25 14:00', 7,3,3, '[2]',   'C', '[]', NULL),
('2026-09-29 10:30', 8,1,1, '[4]',   'K', '[[4,1],[6,1]]', NULL),
('2026-10-08 09:30', 2,2,2, '[2]',   'S', '[[2,1]]', NULL),
('2026-10-14 14:00', 5,1,1, '[3]',   'S', '[[3,1]]', NULL),
('2026-10-20 10:00', 3,3,3, '[1]',   'C', '[]', NULL),
('2026-10-27 15:30', 6,2,2, '[5]',   'S', '[[5,1],[6,1.5]]', NULL),
('2026-11-03 09:00', 4,4,3, '[2]',   'P', '[]', NULL),
('2026-11-06 11:00', 7,1,1, '[4]',   'S', '[[4,1]]', NULL),
('2026-11-12 14:30', 1,2,2, '[7]',   'C', '[]', NULL),
('2026-11-17 10:00', 8,3,3, '[1]',   'N', '[]', '患者临时身体不适，改日再诊'),
('2026-11-18 16:00', 3,1,1, '[2]',   'S', '[[2,1]]', NULL);

-- Use Beijing's calendar day even when the MySQL session uses UTC.
SET @original_time_zone = @@session.time_zone;
SET SESSION time_zone = '+08:00';
SET @demo_today = CURDATE();
SET SESSION time_zone = @original_time_zone;
SET @has_today = EXISTS(SELECT 1 FROM appointment WHERE scheduled_at >= @demo_today
    AND scheduled_at < @demo_today + INTERVAL 1 DAY)
    OR EXISTS(SELECT 1 FROM demo_schedule WHERE DATE(scheduled_at) = @demo_today);
INSERT INTO demo_schedule
SELECT TIMESTAMP(@demo_today, slots.at_time), patient_no, dentist_no, clinic_no, intents, outcome, actual_lines, NULL
FROM (
    SELECT '09:00' at_time, 1 patient_no, 1 dentist_no, 1 clinic_no, '[1,2]' intents, 'A' outcome, '[]' actual_lines
    UNION ALL SELECT '09:30', 5,2,2,'[3]', 'K','[[3,1]]'
    UNION ALL SELECT '10:30', 3,1,1,'[4]', 'V','[]'
    UNION ALL SELECT '14:00', 7,2,2,'[2]', 'V','[]'
    UNION ALL SELECT '15:30', 2,1,1,'[1]', 'V','[]'
    UNION ALL SELECT '16:00', 4,3,3,'[1]', 'V','[]'
) slots WHERE NOT @has_today;

INSERT INTO appointment (scheduled_at, patient_id, dentist_id, clinic_id, arrived, cancelled_at, cancelled_by)
SELECT s.scheduled_at, (SELECT id FROM patient WHERE account_no = CONCAT('P', LPAD(s.patient_no, 6, '0'))),
    (SELECT id FROM dentist WHERE employee_no = CONCAT('D', LPAD(s.dentist_no, 3, '0'))),
    (SELECT id FROM clinic WHERE name = ELT(s.clinic_no, '天河诊所','越秀诊所','福田诊所')),
    s.outcome IN ('A','K','S','N'),
    IF(s.outcome = 'C', TIMESTAMP(DATE(s.scheduled_at) - INTERVAL 1 DAY, '18:00:00'), NULL),
    IF(s.outcome = 'C', '王丽', NULL)
FROM demo_schedule s ORDER BY s.scheduled_at;

SET @first_demo_appointment = LAST_INSERT_ID();
INSERT INTO demo_appointment_id
SELECT s.scheduled_at, a.id FROM demo_schedule s
JOIN patient p ON p.account_no = CONCAT('P', LPAD(s.patient_no, 6, '0'))
JOIN appointment a ON a.scheduled_at = s.scheduled_at AND a.patient_id = p.id AND a.id >= @first_demo_appointment;

INSERT INTO appointment_item (appointment_id, item_id)
SELECT a.id, i.id FROM demo_schedule s JOIN demo_appointment_id a USING(scheduled_at)
JOIN JSON_TABLE(s.intents,'$[*]' COLUMNS(ref INT PATH '$')) j
JOIN demo_item_id i ON i.ref = j.ref;

INSERT INTO visit (appointment_id, dentist_id, clinic_id, arrived_at, registered_at, confirmed_at,
                   problem, special_needs, no_treatment_reason)
SELECT a.id, a.dentist_id, a.clinic_id, s.scheduled_at + INTERVAL 5 MINUTE, s.scheduled_at + INTERVAL 5 MINUTE,
    IF(s.outcome = 'A', NULL, s.scheduled_at + INTERVAL 45 MINUTE),
    CASE WHEN s.outcome = 'N' THEN '复诊评估' WHEN s.outcome <> 'A' THEN '常规检查与治疗' END,
    IF(s.outcome = 'A', NULL, '无'), s.reason
FROM demo_schedule s JOIN demo_appointment_id m USING(scheduled_at)
JOIN appointment a ON a.id = m.id WHERE a.arrived ORDER BY s.scheduled_at;

INSERT INTO treatment (visit_id, item_id, quantity, unit_price, item_name, unit_name)
SELECT v.id, i.id, j.quantity, (SELECT p.unit_price FROM standard_price p
        WHERE p.item_id = i.id AND p.effective_from <= DATE(v.arrived_at) ORDER BY p.effective_from DESC LIMIT 1),
    i.name, i.standard_unit
FROM demo_schedule s JOIN demo_appointment_id m USING(scheduled_at)
JOIN visit v ON v.appointment_id = m.id
JOIN JSON_TABLE(s.actual_lines,'$[*]' COLUMNS(line_no FOR ORDINALITY, ref INT PATH '$[0]',
    quantity DECIMAL(18,6) PATH '$[1]')) j
JOIN demo_item_id mapping ON mapping.ref = j.ref
JOIN treatment_item i ON i.id = mapping.id ORDER BY s.scheduled_at, j.line_no;

INSERT INTO settlement (settlement_no, visit_id, total, issued_at, issued_by)
SELECT CONCAT('JS', DATE_FORMAT(issued, '%Y%m%d'), '-',
              LPAD(ROW_NUMBER() OVER (PARTITION BY DATE(issued) ORDER BY issued), 4, '0')),
    visit_id, total, issued, '王丽'
FROM (
    SELECT v.id AS visit_id, s.scheduled_at + INTERVAL 70 MINUTE AS issued,
        SUM(ROUND(t.quantity * t.unit_price, 2)) AS total
    FROM demo_schedule s JOIN demo_appointment_id m USING(scheduled_at)
    JOIN visit v ON v.appointment_id = m.id JOIN treatment t ON t.visit_id = v.id
    WHERE s.outcome = 'S' GROUP BY v.id, s.scheduled_at
) issued_visits ORDER BY issued;
COMMIT;

DROP TEMPORARY TABLE demo_schedule, demo_item_id, demo_appointment_id;
