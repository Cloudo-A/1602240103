START TRANSACTION;

INSERT INTO quarter_close
    (year, quarter, confirmed_at, first_confirmed_at, company_net_profit, incentive_fund, fund_transfer_at, fund_note)
SELECT 2026, 2, '2026-07-08 16:00:00', '2026-07-08 16:00:00', -5500.00, NULL, '2026-07-08 16:00:00',
    '第二季度系统尚未启用，各诊所只发生筹备期运营费用，没有可分配利润，激励基金转总部统一处理。'
WHERE NOT EXISTS (SELECT 1 FROM quarter_close WHERE year=2026 AND quarter=2);

SET @adc_demo_quarter_id = IF(ROW_COUNT()=1, LAST_INSERT_ID(), NULL);

INSERT INTO dentist_quarter_performance (quarter_close_id, dentist_id, revenue, supply_cost, operating_cost, basis)
SELECT @adc_demo_quarter_id,
    (SELECT id FROM dentist WHERE employee_no=entry.employee_no),
    0.00, 0.00, entry.operating_cost, entry.basis
FROM (
    SELECT 'D001' AS employee_no, 1800.00 AS operating_cost, '天河诊所筹备期运营费用，按财务台账 2026-Q2' AS basis
    UNION ALL SELECT 'D002', 1500.00, '越秀诊所筹备期运营费用，按财务台账 2026-Q2'
    UNION ALL SELECT 'D003', 1200.00, '福田诊所筹备期运营费用（按人头分摊），按财务台账 2026-Q2'
    UNION ALL SELECT 'D004', 1000.00, '福田诊所筹备期运营费用（按人头分摊），按财务台账 2026-Q2'
) entry
WHERE @adc_demo_quarter_id IS NOT NULL;

SET @adc_demo_quarter_id = NULL;
COMMIT;
