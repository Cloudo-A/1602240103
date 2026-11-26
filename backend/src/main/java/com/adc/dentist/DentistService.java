package com.adc.dentist;

import com.adc.common.BusinessException;
import com.adc.common.OperationLogService;
import com.adc.common.VersionedWrites;
import jakarta.persistence.*;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DentistService {
    private final EntityManager em;
    private final QualificationHoldings holdings;
    private final OperationLogService logs;

    DentistService(EntityManager em, QualificationHoldings holdings, OperationLogService logs) {
        this.em = em;
        this.holdings = holdings;
        this.logs = logs;
    }

    List<Dentist.View> list() {
        var dentists = em.createQuery("from Dentist order by id", Dentist.class).getResultList();
        var all = holdings.all();
        return dentists.stream().map(d -> d.view(all.getOrDefault(d.id, List.of()))).toList();
    }

    Dentist.View get(long id) {
        var dentist = em.find(Dentist.class, id);
        if (dentist == null) throw BusinessException.notFound();
        return dentist.view(holdings.forDentist(id));
    }

    @Transactional
    Dentist.View create(DentistInput input) {
        var dentist = new Dentist();
        dentist.apply(input);
        em.persist(dentist);
        holdings.replace(dentist.id, input.qualifications());
        var result = dentist.view(holdings.forDentist(dentist.id));
        logs.record("CREATE", "DENTIST", dentist.id, result);
        return result;
    }

    @Transactional
    Dentist.View update(long id, DentistInput input) {
        var dentist = VersionedWrites.lock(em, Dentist.class, id);
        var before = VersionedWrites.check(input.version(), dentist.version,
            () -> dentist.view(holdings.currentForUpdate(id)));
        if (input.active() == null) throw BusinessException.invalid("active", "请选择使用状态");
        dentist.apply(input);
        holdings.replace(id, input.qualifications());
        VersionedWrites.advance(em, dentist, input.version(), () -> dentist.version);
        var result = dentist.view(holdings.forDentist(id));
        logs.record("UPDATE", "DENTIST", id, Map.of("before", before, "after", result));
        return result;
    }

    @Transactional
    Qualification.View createQualification(Qualification.Input input) {
        var qualification = new Qualification();
        qualification.name = input.name().strip();
        em.persist(qualification);
        var result = new Qualification.View(qualification.id, qualification.name);
        logs.record("CREATE", "QUALIFICATION", qualification.id, result);
        return result;
    }
}
