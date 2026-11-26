package com.adc.quarter;

import com.adc.common.ApiError.FieldIssue;
import com.adc.common.BusinessException;
import com.adc.common.OperationLogService;
import com.adc.visit.VisitQueries;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.adc.quarter.QuarterCalculation.Period;
import static com.adc.quarter.QuarterDtos.*;

@Service
@Transactional(readOnly=true)
class QuarterService {
    private final QuarterQueries queries;
    private final VisitQueries visits;
    private final QuarterCalculation calculation;
    private final OperationLogService logs;
    private final Clock clock;

    QuarterService(QuarterQueries queries, VisitQueries visits, QuarterCalculation calculation,
                   OperationLogService logs, Clock clock) {
        this.queries = queries;
        this.visits = visits;
        this.calculation = calculation;
        this.logs = logs;
        this.clock = clock;
    }

    List<Closed> list() { return queries.list(); }

    Preparation prepare(Period period) {
        return new Preparation(period.year(), period.quarter(), period.end().isBefore(LocalDate.now(clock)),
            queries.find(period),visits.confirmedUnsettled(period.start(),period.end()),queries.roster(period));
    }

    Preview preview(Period period, Input input) {
        return calculation.compute(prepare(period), input.rows()).preview();
    }

    @Transactional
    Closed confirm(Period period, Input input) {
        queries.lockInputs(period);
        var prep = prepare(period);
        var blocked = QuarterCalculation.stateProblems(prep);
        if (!blocked.isEmpty()) throw BusinessException.stateNotAllowed(String.join("；",blocked));
        var computed = calculation.compute(prep,input.rows());
        var preview = computed.preview();
        var issues = new ArrayList<>(computed.issues());
        String note = QuarterCalculation.strip(input.fundNote());
        if (preview.fundNoteRequired() && note == null)
            issues.add(new FieldIssue("fundNote", "公司净利润不为正，请填写基金处理说明"));
        if (!issues.isEmpty()) throw BusinessException.invalid(issues);
        if (!preview.checkToken().equals(input.checkToken())) throw BusinessException.changed(null);
        var saved = queries.replace(period,preview,LocalDateTime.now(clock).withNano(0),note);
        logs.record("CONFIRM_QUARTER","QUARTER_CLOSE",saved.id(),saved);
        return saved;
    }
}
