-- ADC 牙科连锁诊所运营管理系统：表结构
-- 对应报告第十章 16 张业务表；另加账号、操作记录、请求记录 3 张配套表。
-- 相对报告新增的字段：诊所.名称、诊所/牙医/治疗项目.使用状态、就诊.未实施治疗原因、结算.开具时间/开具人。
-- 表名、列名用英文，中文名写在 COMMENT 里。

-- ========== 基础资料 ==========

CREATE TABLE clinic (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '诊所编号',
  name           VARCHAR(60)   NOT NULL COMMENT '诊所名称（新增，便于识别）',
  postal_address VARCHAR(255)  NOT NULL COMMENT '邮政地址',
  manager_name   VARCHAR(60)   NOT NULL COMMENT '主管姓名',
  phone          VARCHAR(30)   NOT NULL COMMENT '电话',
  email          VARCHAR(254)  NOT NULL COMMENT '电子邮箱',
  office_area    DECIMAL(12,2) NOT NULL COMMENT '办公面积（平方米）',
  active         BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '使用状态（新增）：停用后新预约不可选，历史保留',
  version        INT           NOT NULL DEFAULT 0 COMMENT '并发版本号',
  PRIMARY KEY (id),
  UNIQUE KEY uk_clinic_name (name),
  CONSTRAINT ck_clinic_area CHECK (office_area > 0)
) COMMENT '诊所';

CREATE TABLE dentist (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '牙医ID',
  employee_no  VARCHAR(30)  NOT NULL COMMENT '员工编号',
  name         VARCHAR(60)  NOT NULL COMMENT '姓名',
  birth_date   DATE         NOT NULL COMMENT '出生日期',
  home_address VARCHAR(255) NOT NULL COMMENT '家庭地址',
  phone        VARCHAR(30)  NOT NULL COMMENT '电话',
  email        VARCHAR(254) NOT NULL COMMENT '电子邮箱',
  active       BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '使用状态（新增）',
  version      INT          NOT NULL DEFAULT 0 COMMENT '并发版本号',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dentist_employee_no (employee_no)
) COMMENT '牙医';

CREATE TABLE qualification (
  id   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '资质编号',
  name VARCHAR(100) NOT NULL COMMENT '名称',
  PRIMARY KEY (id),
  UNIQUE KEY uk_qualification_name (name)
) COMMENT '专业资质';

CREATE TABLE qualification_holding (
  dentist_id       BIGINT   NOT NULL COMMENT '牙医ID',
  qualification_id BIGINT   NOT NULL COMMENT '资质编号',
  verified_at      DATETIME NULL COMMENT '核实时间：为空表示待核实',
  PRIMARY KEY (dentist_id, qualification_id),
  CONSTRAINT fk_holding_dentist FOREIGN KEY (dentist_id) REFERENCES dentist (id),
  CONSTRAINT fk_holding_qualification FOREIGN KEY (qualification_id) REFERENCES qualification (id)
) COMMENT '资质持有（关联类）';

CREATE TABLE treatment_item (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '项目编号',
  name           VARCHAR(100) NOT NULL COMMENT '名称',
  standard_unit  VARCHAR(30)  NOT NULL COMMENT '标准单位',
  quantity_scale SMALLINT     NOT NULL COMMENT '数量精度（小数位数）',
  active         BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '使用状态（新增）',
  version        INT          NOT NULL DEFAULT 0 COMMENT '并发版本号',
  PRIMARY KEY (id),
  UNIQUE KEY uk_treatment_item_name (name),
  CONSTRAINT ck_item_scale CHECK (quantity_scale BETWEEN 0 AND 6)
) COMMENT '治疗项目';

CREATE TABLE standard_price (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '价格编号',
  item_id        BIGINT        NOT NULL COMMENT '项目编号',
  effective_from DATE          NOT NULL COMMENT '适用起始日期',
  unit_price     DECIMAL(18,2) NOT NULL COMMENT '单位价格（元）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_item_date (item_id, effective_from),
  CONSTRAINT fk_price_item FOREIGN KEY (item_id) REFERENCES treatment_item (id),
  CONSTRAINT ck_price_nonneg CHECK (unit_price >= 0)
) COMMENT '标准价格';

CREATE TABLE allergy (
  id   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '过敏编号',
  name VARCHAR(100) NOT NULL COMMENT '名称',
  PRIMARY KEY (id),
  UNIQUE KEY uk_allergy_name (name)
) COMMENT '过敏项目';

CREATE TABLE patient (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '患者ID',
  account_no          VARCHAR(30)  NOT NULL COMMENT '账号（业务档案号，不是登录账号）',
  name                VARCHAR(60)  NOT NULL COMMENT '姓名',
  birth_date          DATE         NOT NULL COMMENT '出生日期',
  billing_address     VARCHAR(255) NOT NULL COMMENT '账单地址',
  phone               VARCHAR(30)  NOT NULL COMMENT '电话',
  email               VARCHAR(254) NULL COMMENT '电子邮箱',
  emergency_name      VARCHAR(60)  NOT NULL COMMENT '紧急联系人姓名',
  emergency_phone     VARCHAR(30)  NOT NULL COMMENT '紧急联系人电话',
  allergy_verified_at DATETIME     NULL COMMENT '过敏核实时间：为空表示尚未核实，不等于无过敏',
  version             INT          NOT NULL DEFAULT 0 COMMENT '并发版本号',
  PRIMARY KEY (id),
  UNIQUE KEY uk_patient_account_no (account_no),
  KEY idx_patient_name (name),
  KEY idx_patient_phone (phone)
) COMMENT '患者';

CREATE TABLE patient_allergy (
  patient_id BIGINT NOT NULL COMMENT '患者ID',
  allergy_id BIGINT NOT NULL COMMENT '过敏编号',
  PRIMARY KEY (patient_id, allergy_id),
  CONSTRAINT fk_pa_patient FOREIGN KEY (patient_id) REFERENCES patient (id),
  CONSTRAINT fk_pa_allergy FOREIGN KEY (allergy_id) REFERENCES allergy (id)
) COMMENT '患者过敏';

-- ========== 预约与诊疗 ==========

CREATE TABLE appointment (
  id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '预约编号',
  patient_id   BIGINT      NOT NULL COMMENT '患者ID',
  dentist_id   BIGINT      NOT NULL COMMENT '预约牙医ID',
  clinic_id    BIGINT      NOT NULL COMMENT '预约诊所编号',
  scheduled_at DATETIME    NOT NULL COMMENT '预约日期时间',
  arrived      BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '到诊标记',
  cancelled_at DATETIME    NULL COMMENT '取消时间',
  cancelled_by VARCHAR(60) NULL COMMENT '取消经办人',
  version      INT         NOT NULL DEFAULT 0 COMMENT '并发版本号',
  -- 报告 10.3：同一患者或牙医同一时间不能有两条未取消预约。
  -- MySQL 没有部分唯一索引，用生成列实现：已取消的行为 NULL，不参与唯一约束。
  active_slot  DATETIME AS (CASE WHEN cancelled_at IS NULL THEN scheduled_at END) STORED COMMENT '未取消时的预约时间（生成列）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_appt_patient_slot (patient_id, active_slot),
  UNIQUE KEY uk_appt_dentist_slot (dentist_id, active_slot),
  KEY idx_appt_clinic_time (clinic_id, scheduled_at),
  CONSTRAINT fk_appt_patient FOREIGN KEY (patient_id) REFERENCES patient (id),
  CONSTRAINT fk_appt_dentist FOREIGN KEY (dentist_id) REFERENCES dentist (id),
  CONSTRAINT fk_appt_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (id),
  CONSTRAINT ck_appt_cancel CHECK ((cancelled_at IS NULL AND cancelled_by IS NULL) OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL)),
  CONSTRAINT ck_appt_not_both CHECK (NOT (arrived AND cancelled_at IS NOT NULL))
) COMMENT '预约';

CREATE TABLE appointment_item (
  appointment_id BIGINT NOT NULL COMMENT '预约编号',
  item_id        BIGINT NOT NULL COMMENT '项目编号',
  PRIMARY KEY (appointment_id, item_id),
  CONSTRAINT fk_ai_appointment FOREIGN KEY (appointment_id) REFERENCES appointment (id),
  CONSTRAINT fk_ai_item FOREIGN KEY (item_id) REFERENCES treatment_item (id)
) COMMENT '预约治疗项目（意向治疗）';

CREATE TABLE visit (
  id                  BIGINT   NOT NULL AUTO_INCREMENT COMMENT '就诊编号',
  appointment_id      BIGINT   NOT NULL COMMENT '预约编号',
  dentist_id          BIGINT   NOT NULL COMMENT '实际牙医ID',
  clinic_id           BIGINT   NOT NULL COMMENT '实际诊所编号',
  arrived_at          DATETIME NOT NULL COMMENT '实际到诊时间',
  registered_at       DATETIME NOT NULL COMMENT '登记操作时间',
  problem             TEXT     NULL COMMENT '本次问题',
  special_needs       TEXT     NULL COMMENT '特殊需求',
  related_history     TEXT     NULL COMMENT '相关既往病史',
  confirmed_at        DATETIME NULL COMMENT '诊疗确认时间',
  no_treatment_reason TEXT     NULL COMMENT '未实施治疗原因（新增）：有值表示到诊但未实施治疗即结束',
  version             INT      NOT NULL DEFAULT 0 COMMENT '并发版本号',
  PRIMARY KEY (id),
  UNIQUE KEY uk_visit_appointment (appointment_id),
  KEY idx_visit_dentist_time (dentist_id, arrived_at),
  KEY idx_visit_clinic_time (clinic_id, arrived_at),
  CONSTRAINT fk_visit_appointment FOREIGN KEY (appointment_id) REFERENCES appointment (id),
  CONSTRAINT fk_visit_dentist FOREIGN KEY (dentist_id) REFERENCES dentist (id),
  CONSTRAINT fk_visit_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (id),
  CONSTRAINT ck_visit_no_treatment CHECK (no_treatment_reason IS NULL OR confirmed_at IS NOT NULL)
) COMMENT '就诊';

CREATE TABLE treatment (
  id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '治疗明细编号',
  visit_id   BIGINT        NOT NULL COMMENT '就诊编号',
  item_id    BIGINT        NOT NULL COMMENT '项目编号',
  quantity   DECIMAL(18,6) NOT NULL COMMENT '单位数量',
  unit_price DECIMAL(18,2) NOT NULL COMMENT '当次标准单价（快照）',
  item_name  VARCHAR(100)  NOT NULL COMMENT '当次项目名称（快照）',
  unit_name  VARCHAR(30)   NOT NULL COMMENT '当次标准单位（快照）',
  PRIMARY KEY (id),
  KEY idx_treatment_visit (visit_id),
  CONSTRAINT fk_treatment_visit FOREIGN KEY (visit_id) REFERENCES visit (id),
  CONSTRAINT fk_treatment_item FOREIGN KEY (item_id) REFERENCES treatment_item (id),
  CONSTRAINT ck_treatment_qty CHECK (quantity > 0),
  CONSTRAINT ck_treatment_price CHECK (unit_price >= 0)
) COMMENT '实际治疗';

CREATE TABLE settlement (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '结算ID',
  settlement_no VARCHAR(30)   NOT NULL COMMENT '单号',
  visit_id      BIGINT        NOT NULL COMMENT '就诊编号',
  total         DECIMAL(18,2) NOT NULL COMMENT '结算总额（元）',
  issued_at     DATETIME      NOT NULL COMMENT '开具时间（新增）',
  issued_by     VARCHAR(60)   NOT NULL COMMENT '开具人（新增）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_settlement_no (settlement_no),
  UNIQUE KEY uk_settlement_visit (visit_id),
  CONSTRAINT fk_settlement_visit FOREIGN KEY (visit_id) REFERENCES visit (id),
  CONSTRAINT ck_settlement_total CHECK (total >= 0)
) COMMENT '结算（结算单）';

-- ========== 季度经营核算 ==========

CREATE TABLE quarter_close (
  id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '核算编号',
  year               SMALLINT      NOT NULL COMMENT '年度',
  quarter            SMALLINT      NOT NULL COMMENT '季度',
  confirmed_at       DATETIME      NOT NULL COMMENT '确认时间',
  company_net_profit DECIMAL(18,2) NOT NULL COMMENT '公司净利润',
  incentive_fund     DECIMAL(18,2) NULL COMMENT '激励基金总额：仅公司净利润为正时填写',
  fund_transfer_at   DATETIME      NULL COMMENT '基金转处理时间：公司净利润非正时填写',
  fund_note          TEXT          NULL COMMENT '基金处理说明',
  version            INT           NOT NULL DEFAULT 0 COMMENT '并发版本号',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quarter (year, quarter),
  CONSTRAINT ck_quarter_range CHECK (quarter BETWEEN 1 AND 4),
  CONSTRAINT ck_quarter_fund CHECK (
    (company_net_profit > 0 AND incentive_fund IS NOT NULL AND fund_transfer_at IS NULL)
    OR (company_net_profit <= 0 AND incentive_fund IS NULL AND fund_transfer_at IS NOT NULL AND fund_note IS NOT NULL)
  )
) COMMENT '季度核算';

CREATE TABLE dentist_quarter_performance (
  quarter_close_id BIGINT        NOT NULL COMMENT '核算编号',
  dentist_id       BIGINT        NOT NULL COMMENT '牙医ID',
  revenue          DECIMAL(18,2) NOT NULL COMMENT '收入',
  supply_cost      DECIMAL(18,2) NOT NULL COMMENT '医疗用品成本',
  operating_cost   DECIMAL(18,2) NOT NULL COMMENT '运营费用',
  basis            TEXT          NOT NULL COMMENT '核对依据',
  PRIMARY KEY (quarter_close_id, dentist_id),
  CONSTRAINT fk_perf_quarter FOREIGN KEY (quarter_close_id) REFERENCES quarter_close (id),
  CONSTRAINT fk_perf_dentist FOREIGN KEY (dentist_id) REFERENCES dentist (id),
  CONSTRAINT ck_perf_cost CHECK (supply_cost >= 0 AND operating_cost >= 0 AND revenue >= 0)
) COMMENT '牙医季度业绩';

-- ========== 配套表：账号、操作记录、请求去重 ==========

CREATE TABLE app_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  username      VARCHAR(40)  NOT NULL COMMENT '登录名',
  password_hash VARCHAR(100) NOT NULL COMMENT '密码哈希（BCrypt）',
  display_name  VARCHAR(60)  NOT NULL COMMENT '显示姓名',
  role          VARCHAR(20)  NOT NULL COMMENT '岗位：FRONT_DESK 前台 / DENTIST 牙医 / HQ 总部',
  dentist_id    BIGINT       NULL COMMENT '牙医岗位绑定的牙医ID',
  active        BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '是否可登录',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username),
  UNIQUE KEY uk_user_dentist (dentist_id),
  CONSTRAINT fk_user_dentist FOREIGN KEY (dentist_id) REFERENCES dentist (id),
  CONSTRAINT ck_user_role CHECK (role IN ('FRONT_DESK', 'DENTIST', 'HQ')),
  CONSTRAINT ck_user_dentist CHECK ((role = 'DENTIST') = (dentist_id IS NOT NULL))
) COMMENT '系统账号';

CREATE TABLE operation_log (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  user_id     BIGINT      NOT NULL COMMENT '操作人用户ID',
  username    VARCHAR(40) NOT NULL COMMENT '操作人登录名',
  occurred_at DATETIME    NOT NULL COMMENT '操作时间',
  action      VARCHAR(40) NOT NULL COMMENT '操作，例如 CANCEL_APPOINTMENT',
  target_type VARCHAR(40) NOT NULL COMMENT '对象类型，例如 appointment',
  target_id   BIGINT      NULL COMMENT '对象ID',
  detail      TEXT        NULL COMMENT '变更内容',
  PRIMARY KEY (id),
  KEY idx_log_target (target_type, target_id),
  KEY idx_log_time (occurred_at)
) COMMENT '操作记录（报告 Q3 可追溯性）';

CREATE TABLE request_record (
  request_id   VARCHAR(64) NOT NULL COMMENT '请求标识（前端每次确认生成）',
  user_id      BIGINT      NOT NULL COMMENT '用户ID',
  action       VARCHAR(40) NOT NULL COMMENT '操作',
  content_hash VARCHAR(64) NOT NULL COMMENT '请求内容摘要',
  result_ref   VARCHAR(100) NULL COMMENT '成功结果引用，例如结算单号',
  created_at   DATETIME    NOT NULL COMMENT '首次处理时间',
  PRIMARY KEY (request_id)
) COMMENT '请求记录（报告 11.3 重复提交去重）';
