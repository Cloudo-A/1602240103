package com.adc.visit;

import com.adc.auth.CurrentUser;
import com.adc.common.*;
import com.adc.common.ApiError.FieldIssue;
import com.adc.patient.PatientService;
import com.adc.visit.VisitDtos.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@Transactional
class VisitService {
    private final EntityManager em;
    private final JdbcClient jdbc;
    private final VisitQueries queries;
    private final VisitPricing pricing;
    private final PatientService patients;
    private final OperationLogService logs;
    private final Clock clock;

    VisitService(EntityManager em, JdbcClient jdbc, VisitQueries queries, VisitPricing pricing,
                 PatientService patients, OperationLogService logs, Clock clock) {
        this.em=em;
        this.jdbc=jdbc;
        this.queries=queries;
        this.pricing=pricing;
        this.patients=patients;
        this.logs=logs;
        this.clock=clock;
    }

    @Transactional(readOnly=true)
    Preview preview(long id, CurrentUser user, JsonNode treatments) {
        Visit visit = em.find(Visit.class,id);
        if (visit == null) throw BusinessException.notFound();
        VisitQueries.authorize(user,visit.dentistId);
        var priced = pricing.price(visit.arrivedAt.toLocalDate(),treatments);
        return new Preview(priced.lines(),priced.issues().stream().map(FieldIssue::message).toList());
    }

    Detail confirm(long id, CurrentUser user, Confirm form) {
        var visit = VersionedWrites.lock(em,Visit.class,id);
        VisitQueries.authorize(user,visit.dentistId);
        var before = queries.detail(id,user);
        if (Set.of("SETTLED","NO_TREATMENT").contains(before.status())) {
            throw BusinessException.stateNotAllowed("本次就诊已结算或已按未实施治疗结束，不能再确认诊疗");
        }
        VersionedWrites.check(form.version(),visit.version,() -> before);
        var priced = pricing.price(visit.arrivedAt.toLocalDate(),form.treatments());
        List<FieldIssue> issues = new ArrayList<>(priced.issues());
        String problem = optional(form.problem());
        if (problem == null) issues.add(new FieldIssue("problem","请填写患者问题描述"));
        List<Long> allergyIds = new ArrayList<>();
        if (form.allergyIds() == null || !form.allergyIds().isArray()) {
            issues.add(new FieldIssue("allergyIds","请提交已核实的过敏项目数组"));
        } else {
            for (var value : form.allergyIds()) {
                Long allergyId = VisitPricing.id(value);
                if (allergyId == null || allergyId <= 0) {
                    issues.add(new FieldIssue("allergyIds","过敏项目编号无效"));
                    break;
                }
                allergyIds.add(allergyId);
            }
        }
        if (!issues.isEmpty()) throw BusinessException.invalid(issues);

        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        patients.verifyAllergies(before.patient().id(),allergyIds,now);
        jdbc.sql("DELETE FROM treatment WHERE visit_id=?").param(id).update();
        for (var line : priced.lines()) {
            jdbc.sql("INSERT INTO treatment(visit_id,item_id,quantity,unit_price,item_name,unit_name) VALUES(?,?,?,?,?,?)")
                .params(id,line.itemId(),line.quantity(),line.unitPrice(),line.itemName(),line.unitName()).update();
        }
        visit.problem=problem;
        visit.specialNeeds=optional(form.specialNeeds());
        visit.relatedHistory=optional(form.relatedHistory());
        visit.confirmedAt=now;
        VersionedWrites.advance(em,visit,form.version(),() -> visit.version);
        var after = queries.detail(id,user);
        logs.record("CONFIRM_VISIT","VISIT",id,Map.of("before",before,"after",after));
        return after;
    }

    Detail end(long id, CurrentUser user, End form) {
        var visit = VersionedWrites.lock(em,Visit.class,id);
        VisitQueries.authorize(user,visit.dentistId);
        var before = queries.detail(id,user);
        if (!"UNCONFIRMED".equals(before.status())) {
            throw BusinessException.stateNotAllowed("只有尚未确认的就诊才能按未实施治疗结束");
        }
        VersionedWrites.check(form.version(),visit.version,() -> before);
        var reason = optional(form.reason());
        if (reason == null) throw BusinessException.invalid("reason","请填写未实施治疗原因");
        jdbc.sql("DELETE FROM treatment WHERE visit_id=?").param(id).update();
        visit.noTreatmentReason=reason;
        visit.problem=optional(form.problem());
        visit.confirmedAt=LocalDateTime.now(clock).withNano(0);
        VersionedWrites.advance(em,visit,form.version(),() -> visit.version);
        var after = queries.detail(id,user);
        logs.record("END_WITHOUT_TREATMENT","VISIT",id,Map.of("before",before,"after",after));
        return after;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
