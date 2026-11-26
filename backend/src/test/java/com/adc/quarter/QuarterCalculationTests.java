package com.adc.quarter;

import com.adc.common.ApiError.FieldIssue;
import com.adc.visit.VisitDtos.Summary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static com.adc.quarter.QuarterDtos.*;
import static org.assertj.core.api.Assertions.assertThat;

class QuarterCalculationTests {
    private final QuarterCalculation calculation = new QuarterCalculation(new ObjectMapper());

    private PreparedRow dentist(long id, String revenue) {
        return new PreparedRow(id, "D00" + id, "牙医" + id, new BigDecimal(revenue), null, null, null);
    }

    @Test void countsBothBusinessDayEndpointsAndSkipsWeekends() {
        assertThat(QuarterCalculation.workdays(LocalDate.parse("2026-09-30"),LocalDate.parse("2026-10-09"))).isEqualTo(7);
        assertThat(QuarterCalculation.workdays(LocalDate.parse("2026-06-30"),LocalDate.parse("2026-07-08"))).isEqualTo(6);
        assertThat(QuarterCalculation.workdays(LocalDate.parse("2027-12-31"),LocalDate.parse("2028-01-02"))).isZero();
        assertThat(QuarterCalculation.workdays(LocalDate.parse("2027-12-31"),LocalDate.parse("2028-01-03"))).isOne();
    }

    @Test void roundsFundHalfUpAndKeysNormalizedInputByDentist() {
        var prep = new Preparation(2026,3,true,null,List.of(),List.of(dentist(1,"2.05"),dentist(2,"3.00")));
        var first = new Cost(1L,new BigDecimal("0.00"),new BigDecimal("1.00"),"核算一");
        var second = new Cost(2L,BigDecimal.ZERO,BigDecimal.ZERO,"台账二");
        var preview = calculation.compute(prep,List.of(second,first)).preview();
        assertThat(preview.companyNetProfit()).isEqualByComparingTo("4.05");
        assertThat(preview.incentiveFund()).isEqualByComparingTo("0.41");
        assertThat(preview.problems()).isEmpty();
        assertThat(preview.checkToken()).isNotBlank();
        var canonical = new Cost(1L,BigDecimal.ZERO,BigDecimal.ONE,"  核算一  ");
        assertThat(calculation.compute(prep,List.of(canonical,second)).preview().checkToken()).isEqualTo(preview.checkToken());
        var changedRevenue = new Preparation(2026,3,true,null,List.of(),List.of(dentist(1,"3.05"),dentist(2,"3.00")));
        assertThat(calculation.compute(changedRevenue,List.of(canonical,second)).preview().checkToken()).isNotEqualTo(preview.checkToken());
    }

    @Test void keepsValidRowsAndReportsBothStateProblemsTogetherWithIndexedContentErrors() {
        var unpaid = new Summary(99L,"CONFIRMED",null,null,null,null,BigDecimal.ONE);
        var prep = new Preparation(2026,4,false,null,List.of(unpaid,unpaid),
            List.of(dentist(1,"0.00"),dentist(2,"3.00"),dentist(3,"0.00")));
        var first = new Cost(1L,new BigDecimal("0.001"),BigDecimal.ONE.negate()," ");
        var second = new Cost(2L,BigDecimal.ZERO,BigDecimal.ZERO,"依据");
        var result = calculation.compute(prep,List.of(second,first,second,new Cost(4L,null,null,null)));
        assertThat(result.issues()).extracting(FieldIssue::field).contains("rows","rows[1].supplyCost",
            "rows[1].operatingCost","rows[1].basis","rows[2].dentistId","rows[3].dentistId");
        var preview = result.preview();
        assertThat(preview.rows().get(0).netProfit()).isNull();
        assertThat(preview.rows().get(1).netProfit()).isEqualByComparingTo("3.00");
        assertThat(preview.rows().get(2).netProfit()).isNull();
        assertThat(preview.companyNetProfit()).isNull();
        assertThat(preview.incentiveFund()).isNull();
        assertThat(preview.checkToken()).isNull();
        assertThat(preview.problems()).anyMatch(p -> p.startsWith("季度还没有结束"))
            .anyMatch(p -> p.startsWith("本季度还有 2 次已确认但未开结算单的就诊"));
    }

    @Test void nonpositiveCompanyProfitRequiresExplanationAndNoAccrual() {
        var prep = new Preparation(2026,2,true,null,List.of(),List.of(dentist(1,"0.00")));
        for (BigDecimal cost : List.of(BigDecimal.ZERO,BigDecimal.ONE)) {
            var result = calculation.compute(prep,List.of(new Cost(1L,cost,BigDecimal.ZERO,"账册"))).preview();
            assertThat(result.companyNetProfit()).isEqualByComparingTo(cost.negate());
            assertThat(result.fundNoteRequired()).isTrue();
            assertThat(result.incentiveFund()).isNull();
            assertThat(result.checkToken()).isNotBlank();
        }
    }
}
