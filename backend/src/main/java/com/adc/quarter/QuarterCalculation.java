package com.adc.quarter;

import com.adc.common.ApiError.FieldIssue;
import com.adc.common.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import static com.adc.quarter.QuarterDtos.*;

@Component
class QuarterCalculation {
    private static final BigDecimal LIMIT = new BigDecimal("10000000000000000");
    private final ObjectMapper json;

    QuarterCalculation(ObjectMapper json) { this.json = json; }

    record Period(int year, int quarter, LocalDate start, LocalDate end) {
        static Period of(int year, int quarter) {
            if (quarter < 1 || quarter > 4) throw BusinessException.invalid("quarter", "季度应为1至4");
            if (year < 1000 || year > 9999) throw BusinessException.invalid("year", "请输入有效年度");
            var start = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
            return new Period(year, quarter, start, start.plusMonths(3).minusDays(1));
        }
    }

    static int workdays(LocalDate quarterEnd, LocalDate firstConfirmation) {
        long days = ChronoUnit.DAYS.between(quarterEnd, firstConfirmation);
        if (days <= 0) return 0;
        int weekdays = Math.toIntExact(days / 7 * 5);
        for (int i = 1; i <= days % 7; i++) {
            if (quarterEnd.plusDays(i).getDayOfWeek().getValue() <= 5) weekdays++;
        }
        return weekdays;
    }

    record Computed(Preview preview, List<FieldIssue> issues) {}
    private record Basis(int year, int quarter, List<Row> rows) {}

    Computed compute(Preparation prep, List<Cost> input) {
        List<FieldIssue> issues = new ArrayList<>();
        input = input == null ? List.of() : input;
        Set<Long> roster = new HashSet<>();
        prep.rows().forEach(row -> roster.add(row.dentistId()));
        Map<Long,Integer> indices = new HashMap<>();
        for (int i = 0; i < input.size(); i++) {
            var cost = input.get(i);
            Long id = cost == null ? null : cost.dentistId();
            String field = "rows[" + i + "].dentistId";
            if (!roster.contains(id)) issues.add(new FieldIssue(field, "第" + (i + 1) + "行牙医不在本季度名单中"));
            else if (indices.putIfAbsent(id, i) != null) issues.add(new FieldIssue(field, "第" + (i + 1) + "行牙医重复"));
        }
        List<Row> rows = new ArrayList<>();
        BigDecimal company = BigDecimal.ZERO.setScale(2);
        for (var dentist : prep.rows()) {
            Integer index = indices.get(dentist.dentistId());
            BigDecimal supply = null, operating = null;
            String basis = null;
            if (index == null) {
                issues.add(new FieldIssue("rows", "缺少牙医" + dentist.name() + "的核算行"));
            } else {
                var cost = input.get(index);
                String field = "rows[" + index + "].";
                supply = money(cost.supplyCost(), field+"supplyCost", dentist.name()+"的医疗用品成本", issues);
                operating = money(cost.operatingCost(), field+"operatingCost", dentist.name()+"的运营费用", issues);
                basis = strip(cost.basis());
                if (basis == null) issues.add(new FieldIssue(field+"basis", "请填写" + dentist.name() + "的核对依据"));
            }
            BigDecimal net = supply == null || operating == null ? null : dentist.revenue().subtract(supply).subtract(operating);
            rows.add(new Row(dentist.dentistId(), dentist.employeeNo(), dentist.name(), dentist.revenue(), supply, operating, basis, net));
            company = company == null || net == null ? null : company.add(net);
            if (dentist.revenue().abs().compareTo(LIMIT) >= 0)
                issues.add(new FieldIssue("rows", dentist.name()+"的收入超出可保存范围"));
        }
        if (company != null && company.abs().compareTo(LIMIT) >= 0)
            issues.add(new FieldIssue("rows", "公司净利润超出可保存范围"));
        List<String> problems = new ArrayList<>();
        issues.forEach(issue -> problems.add(issue.message()));
        problems.addAll(stateProblems(prep));
        boolean noteRequired = company != null && company.signum() <= 0;
        BigDecimal fund = company != null && company.signum() > 0
            ? company.movePointLeft(1).setScale(2, RoundingMode.HALF_UP) : null;
        String token = problems.isEmpty() ? token(new Basis(prep.year(), prep.quarter(), rows)) : null;
        return new Computed(new Preview(rows, company, fund, noteRequired, problems, token), issues);
    }

    static List<String> stateProblems(Preparation prep) {
        List<String> problems = new ArrayList<>();
        if (!prep.quarterEnded()) problems.add("季度还没有结束，目前只能预览");
        if (!prep.unsettledVisits().isEmpty())
            problems.add("本季度还有 " + prep.unsettledVisits().size() + " 次已确认但未开结算单的就诊，请前台开单后再确认");
        return problems;
    }

    static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private BigDecimal money(BigDecimal value, String field, String label, List<FieldIssue> issues) {
        String message;
        if (value == null) message = "请填写" + label;
        else if (value.signum() < 0) message = label + "不能为负数";
        else if (value.stripTrailingZeros().scale() > 2) message = label + "最多两位小数";
        else if (value.compareTo(LIMIT) >= 0) message = label + "超出可保存范围";
        else return value.setScale(2);
        issues.add(new FieldIssue(field,message));
        return null;
    }

    private String token(Basis basis) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(basis));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
