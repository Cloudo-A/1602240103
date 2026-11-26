INSERT INTO clinic (name, postal_address, manager_name, phone, email, office_area) VALUES
('天河诊所', '广州市天河区天河路 385 号 3 楼', '张敏', '020-38880001', 'tianhe@adc.example', 180.5),
('越秀诊所', '广州市越秀区中山五路 68 号 2 楼', '刘洋', '020-83330002', 'yuexiu@adc.example', 136),
('福田诊所', '深圳市福田区深南大道 6008 号 5 楼', '黄蕾', '0755-82220003', 'futian@adc.example', 152.25);

INSERT INTO qualification (name) VALUES
('口腔全科'),
('牙体牙髓'),
('口腔正畸'),
('口腔种植'),
('儿童口腔');

INSERT INTO dentist (employee_no, name, birth_date, home_address, phone, email) VALUES
('D003', '黄晓雯', '1990-12-21', '深圳市福田区福华路 18 号', '13800000003', 'd003@adc.example'),
('D004', '吴志强', '1979-06-30', '深圳市南山区科技园南路 1 号', '13800000004', 'd004@adc.example');

INSERT INTO qualification_holding (dentist_id, qualification_id, verified_at) VALUES
((SELECT id FROM dentist WHERE employee_no = 'D001'), (SELECT id FROM qualification WHERE name = '口腔全科'), '2026-01-05 10:00:00'),
((SELECT id FROM dentist WHERE employee_no = 'D001'), (SELECT id FROM qualification WHERE name = '牙体牙髓'), '2026-01-05 10:00:00'),
((SELECT id FROM dentist WHERE employee_no = 'D002'), (SELECT id FROM qualification WHERE name = '口腔全科'), '2026-01-06 09:30:00'),
((SELECT id FROM dentist WHERE employee_no = 'D002'), (SELECT id FROM qualification WHERE name = '口腔种植'), '2026-02-10 14:00:00'),
((SELECT id FROM dentist WHERE employee_no = 'D003'), (SELECT id FROM qualification WHERE name = '口腔全科'), '2026-01-08 11:00:00'),
((SELECT id FROM dentist WHERE employee_no = 'D003'), (SELECT id FROM qualification WHERE name = '口腔正畸'), NULL),
((SELECT id FROM dentist WHERE employee_no = 'D004'), (SELECT id FROM qualification WHERE name = '口腔全科'), '2026-01-08 11:00:00'),
((SELECT id FROM dentist WHERE employee_no = 'D004'), (SELECT id FROM qualification WHERE name = '儿童口腔'), '2026-03-02 15:20:00');

INSERT INTO treatment_item (name, standard_unit, quantity_scale) VALUES
('口腔检查', '次', 0),
('洁牙', '次', 0),
('树脂补牙', '颗', 0),
('根管治疗', '根', 0),
('拔牙', '颗', 0),
('局部麻醉', '毫升', 1),
('正畸复诊调整', '次', 0);

INSERT INTO standard_price (item_id, effective_from, unit_price) VALUES
((SELECT id FROM treatment_item WHERE name = '口腔检查'), '2026-01-01', 50),
((SELECT id FROM treatment_item WHERE name = '洁牙'), '2026-01-01', 120),
((SELECT id FROM treatment_item WHERE name = '洁牙'), '2026-10-01', 150),
((SELECT id FROM treatment_item WHERE name = '树脂补牙'), '2026-01-01', 280),
((SELECT id FROM treatment_item WHERE name = '根管治疗'), '2026-01-01', 600),
((SELECT id FROM treatment_item WHERE name = '拔牙'), '2026-01-01', 200),
((SELECT id FROM treatment_item WHERE name = '局部麻醉'), '2026-01-01', 30),
((SELECT id FROM treatment_item WHERE name = '正畸复诊调整'), '2026-01-01', 400),
((SELECT id FROM treatment_item WHERE name = '局部麻醉'), '2026-07-01', 35);

INSERT INTO allergy (name) VALUES
('青霉素'),
('利多卡因'),
('乳胶'),
('碘伏');

INSERT INTO patient (account_no, name, birth_date, billing_address, phone, email, emergency_name, emergency_phone, allergy_verified_at) VALUES
('P000001', '张伟', '1988-02-14', '广州市天河区体育西路 101 号', '13900000001', 'zhangwei@example.com', '张芳', '13900001001', '2026-09-12 10:20:00'),
('P000002', '李娜', '1995-07-30', '广州市越秀区北京路 22 号', '13900000002', NULL, '李强', '13900001002', '2026-10-08 09:40:00'),
('P000003', '王磊', '1979-11-05', '深圳市福田区华强北路 3 号', '13900000003', 'wanglei@example.com', '王静', '13900001003', NULL),
('P000004', '刘洋', '2001-03-18', '广州市海珠区新港中路 135 号', '13900000004', NULL, '刘军', '13900001004', NULL),
('P000005', '陈静', '1992-08-09', '深圳市南山区南海大道 2000 号', '13900000005', 'chenjing@example.com', '陈明', '13900001005', '2026-08-20 15:00:00'),
('P000006', '杨帆', '1985-12-01', '广州市白云区机场路 88 号', '13900000006', NULL, '杨柳', '13900001006', NULL),
('P000007', '赵敏', '1998-05-25', '广州市天河区珠江新城华夏路 10 号', '13900000007', 'zhaomin@example.com', '赵刚', '13900001007', '2026-07-15 11:10:00'),
('P000008', '张伟', '1990-06-06', '深圳市罗湖区人民南路 1 号', '13900000008', NULL, '张丽', '13900001008', NULL);

INSERT INTO patient_allergy (patient_id, allergy_id) VALUES
((SELECT id FROM patient WHERE account_no = 'P000001'), (SELECT id FROM allergy WHERE name = '青霉素')),
((SELECT id FROM patient WHERE account_no = 'P000003'), (SELECT id FROM allergy WHERE name = '利多卡因')),
((SELECT id FROM patient WHERE account_no = 'P000003'), (SELECT id FROM allergy WHERE name = '乳胶')),
((SELECT id FROM patient WHERE account_no = 'P000005'), (SELECT id FROM allergy WHERE name = '碘伏')),
((SELECT id FROM patient WHERE account_no = 'P000007'), (SELECT id FROM allergy WHERE name = '青霉素')),
((SELECT id FROM patient WHERE account_no = 'P000007'), (SELECT id FROM allergy WHERE name = '利多卡因'));

UPDATE patient_number
SET last_number = GREATEST(last_number, (
    SELECT MAX(CAST(SUBSTRING(account_no, 2) AS UNSIGNED))
    FROM patient WHERE account_no REGEXP '^P[0-9]{6}$'
))
WHERE singleton = 1;
