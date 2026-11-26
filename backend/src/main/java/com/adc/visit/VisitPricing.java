package com.adc.visit;

import com.adc.common.ApiError.FieldIssue;
import com.adc.visit.VisitDtos.PriceLine;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
class VisitPricing {
    private static final BigDecimal SETTLEMENT_LIMIT = new BigDecimal("10000000000000000");
    record Result(List<PriceLine> lines, List<FieldIssue> issues) {}
    private record Item(long id, String name, String unit, int scale, BigDecimal price, LocalDate effective) {}
    private final JdbcClient jdbc;

    VisitPricing(JdbcClient jdbc) { this.jdbc = jdbc; }

    static Long id(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) return null;
        return value.asLong();
    }

    Result price(LocalDate day, JsonNode treatments) {
        List<PriceLine> lines = new ArrayList<>();
        List<FieldIssue> issues = new ArrayList<>();
        if (treatments == null || !treatments.isArray() || treatments.isEmpty()) {
            issues.add(new FieldIssue("treatments","请至少添加一项实际治疗"));
            return new Result(lines,issues);
        }
        Set<Long> ids = new HashSet<>();
        for (var entry : treatments) {
            Long id = id(entry.get("itemId"));
            if (id != null) ids.add(id);
        }
        Map<Long,Item> items = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.sql("""
                SELECT i.id,i.name,i.standard_unit,i.quantity_scale,p.unit_price,p.effective_from
                FROM treatment_item i LEFT JOIN standard_price p ON p.id=(
                    SELECT sp.id FROM standard_price sp WHERE sp.item_id=i.id AND sp.effective_from<=:day
                    ORDER BY sp.effective_from DESC LIMIT 1
                ) WHERE i.id IN (:ids)
                """).param("ids",ids).param("day",day).query((r,n) -> new Item(r.getLong(1),r.getString(2),r.getString(3),
                    r.getInt(4),r.getBigDecimal(5),r.getObject(6,LocalDate.class))).list().forEach(item -> items.put(item.id,item));
        }
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < treatments.size(); i++) {
            JsonNode row = treatments.get(i);
            Item item = items.get(id(row.get("itemId")));
            String field = "treatments[" + i + "]";
            if (item == null) {
                issues.add(new FieldIssue(field + ".itemId","第" + (i+1) + "行治疗项目不存在"));
                continue;
            }
            int before = issues.size();
            if (!seen.add(item.id)) issues.add(new FieldIssue(field+".itemId",item.name+"重复，请合并为一行"));
            JsonNode quantity = row.get("quantity");
            BigDecimal q = quantity != null && quantity.isNumber() ? quantity.decimalValue() : null;
            if (q == null || q.signum() <= 0) {
                issues.add(new FieldIssue(field+".quantity",item.name+"的数量必须是大于0的数值"));
            } else if (q.stripTrailingZeros().scale() > item.scale) {
                issues.add(new FieldIssue(field+".quantity",item.name+"的数量最多允许"+item.scale+"位小数"));
            } else if (q.compareTo(new BigDecimal("1000000000000")) >= 0) {
                issues.add(new FieldIssue(field+".quantity",item.name+"的数量超过可保存范围"));
            }
            if (item.price == null) issues.add(new FieldIssue(field+".itemId",item.name+"在到诊日期"+day+"没有适用价格"));
            if (issues.size() == before) {
                var subtotal = q.multiply(item.price).setScale(2, RoundingMode.HALF_UP);
                lines.add(new PriceLine(item.id,item.name,item.unit,q,item.price,subtotal,item.effective));
            }
        }
        var total = lines.stream().map(PriceLine::subtotal).reduce(BigDecimal.ZERO,BigDecimal::add);
        if (total.compareTo(SETTLEMENT_LIMIT) >= 0) {
            issues.add(new FieldIssue("treatments","治疗总额超过结算单可保存的金额范围"));
        }
        return new Result(lines,issues);
    }
}
