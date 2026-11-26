INSERT INTO dentist (employee_no, name, birth_date, home_address, phone, email) VALUES
('D001','林嘉文','1985-03-12','示例地址一','13800000001','d001@adc.example'),
('D002','周明远','1988-06-18','示例地址二','13800000002','d002@adc.example');

SET @demo_hash = '$2b$12$A7WEnl4jkSyLiKQNciQIKuWnAX2USEA.Qy0L4rKuo5i2P4nMVDEuq';
INSERT INTO app_user (username, password_hash, display_name, role, dentist_id) VALUES
('front1', @demo_hash, '王丽', 'FRONT_DESK', NULL),
('dentist1', @demo_hash, '林嘉文', 'DENTIST', (SELECT id FROM dentist WHERE employee_no='D001')),
('dentist2', @demo_hash, '周明远', 'DENTIST', (SELECT id FROM dentist WHERE employee_no='D002')),
('hq1', @demo_hash, '陈总部', 'HQ', NULL);
