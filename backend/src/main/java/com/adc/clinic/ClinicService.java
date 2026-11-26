package com.adc.clinic;

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
class ClinicService {
    private final EntityManager em;
    private final OperationLogService logs;

    ClinicService(EntityManager em, OperationLogService logs) {
        this.em = em;
        this.logs = logs;
    }

    List<Clinic.View> list() {
        return em.createQuery("from Clinic order by id", Clinic.class).getResultList()
            .stream().map(Clinic::view).toList();
    }

    Clinic.View get(long id) {
        var clinic = em.find(Clinic.class, id);
        if (clinic == null) throw BusinessException.notFound();
        return clinic.view();
    }

    @Transactional
    Clinic.View create(ClinicInput input) {
        var clinic = new Clinic();
        clinic.apply(input);
        em.persist(clinic);
        var result = clinic.view();
        logs.record("CREATE", "CLINIC", clinic.id, result);
        return result;
    }

    @Transactional
    Clinic.View update(long id, ClinicInput input) {
        var clinic = VersionedWrites.lock(em, Clinic.class, id);
        var before = VersionedWrites.check(input.version(), clinic.version, clinic::view);
        if (input.active() == null) throw BusinessException.invalid("active", "请选择使用状态");
        clinic.apply(input);
        VersionedWrites.advance(em, clinic, input.version(), () -> clinic.version);
        var result = clinic.view();
        logs.record("UPDATE", "CLINIC", id, Map.of("before", before, "after", result));
        return result;
    }
}
