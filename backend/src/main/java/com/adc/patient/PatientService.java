package com.adc.patient;

import com.adc.common.BusinessException;
import com.adc.common.OperationLogService;
import com.adc.common.VersionedWrites;
import com.adc.patient.PatientController.*;
import com.adc.visit.VisitQueries;
import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PatientService {
    private static final String SUMMARY = "SELECT id, account_no, name, birth_date, phone FROM patient ";
    private static final String MATCH = """
            WHERE LOCATE(:keyword, name) > 0 OR LOCATE(:keyword, phone) > 0
               OR LOCATE(LOWER(:keyword), LOWER(account_no)) > 0
            """;

    private final EntityManager em;
    private final JdbcClient jdbc;
    private final OperationLogService logs;
    private final VisitQueries visits;

    PatientService(EntityManager em, JdbcClient jdbc, OperationLogService logs, VisitQueries visits) {
        this.em = em;
        this.jdbc = jdbc;
        this.logs = logs;
        this.visits = visits;
    }

    Page search(String keyword, int page, int size) {
        if (page < 0) throw BusinessException.invalid("page", "页码不能小于0");
        if (size < 1) throw BusinessException.invalid("size", "每页条数必须大于0");
        long offset = (long) page * size;
        var items = jdbc.sql(SUMMARY + MATCH + " ORDER BY account_no LIMIT :limit OFFSET :offset")
                .param("keyword", keyword).param("limit", size).param("offset", offset)
                .query(Summary.class).list();
        long total = jdbc.sql("SELECT COUNT(*) FROM patient " + MATCH)
                .param("keyword", keyword).query(Long.class).single();
        return new Page(items, total);
    }

    Detail get(long id) {
        Patient patient = em.find(Patient.class, id);
        if (patient == null) throw BusinessException.notFound();
        return detail(patient);
    }

    @Transactional
    Detail create(Form form) {
        int last = jdbc.sql("SELECT last_number FROM patient_number WHERE singleton = 1 FOR UPDATE")
                .query(Integer.class).single();
        if (!Boolean.TRUE.equals(form.confirmNotDuplicate())) {
            var candidates = jdbc.sql(SUMMARY + """
                    WHERE name = :name AND (birth_date = :birthDate OR phone = :phone) ORDER BY account_no
                    """).param("name", form.name().strip()).param("birthDate", form.birthDate())
                    .param("phone", form.phone().strip()).query(Summary.class).list();
            if (!candidates.isEmpty()) {
                throw BusinessException.conflict("存在同名且出生日期或电话相同的患者，请核对", candidates);
            }
        }
        if (last >= 999999) throw BusinessException.conflict("患者档案号已用完");
        Set<Long> allergies = checkedAllergies(form);
        int number = last + 1;
        jdbc.sql("UPDATE patient_number SET last_number = ? WHERE singleton = 1").param(number).update();
        Patient patient = new Patient();
        patient.accountNo = String.format(Locale.ROOT, "P%06d", number);
        apply(patient, form);
        em.persist(patient);
        em.flush();
        insertAllergies(patient.id, allergies);
        Detail created = detail(patient);
        logs.record("CREATE", "PATIENT", patient.id, created);
        return created;
    }

    @Transactional
    Detail update(long id, Form form) {
        Patient patient = VersionedWrites.lock(em, Patient.class, id);
        Detail before = VersionedWrites.check(form.version(), patient.version, () -> detail(patient));
        Set<Long> allergies = checkedAllergies(form);
        apply(patient, form);
        VersionedWrites.advance(em, patient, form.version(), () -> patient.version);
        jdbc.sql("DELETE FROM patient_allergy WHERE patient_id = ?").param(id).update();
        insertAllergies(id, allergies);
        Detail after = detail(patient);
        logs.record("UPDATE", "PATIENT", id, Map.of("before", before, "after", after));
        return after;
    }

    private static void apply(Patient patient, Form form) {
        patient.name = form.name().strip();
        patient.birthDate = form.birthDate();
        patient.billingAddress = form.billingAddress().strip();
        patient.phone = form.phone().strip();
        patient.email = form.email() == null || form.email().isBlank() ? null : form.email().strip();
        patient.emergencyName = form.emergencyName().strip();
        patient.emergencyPhone = form.emergencyPhone().strip();
    }

    private Set<Long> checkedAllergies(Form form) {
        if (form.allergyIds() == null || form.allergyIds().isEmpty()) return Set.of();
        return checkedAllergies(form.allergyIds());
    }

    private Set<Long> checkedAllergies(Collection<Long> values) {
        if (values.isEmpty()) return Set.of();
        Set<Long> ids = new LinkedHashSet<>(values);
        long found = jdbc.sql("SELECT COUNT(*) FROM allergy WHERE id IN (:ids)")
                .param("ids", ids).query(Long.class).single();
        if (found != ids.size()) throw BusinessException.invalid("allergyIds", "包含不存在的过敏项目");
        return ids;
    }

    @Transactional
    public void verifyAllergies(long patientId, Collection<Long> allergyIds, LocalDateTime verifiedAt) {
        var patient = VersionedWrites.lock(em, Patient.class, patientId);
        var ids = checkedAllergies(allergyIds);
        int previous = patient.version;
        patient.allergyVerifiedAt = verifiedAt;
        jdbc.sql("DELETE FROM patient_allergy WHERE patient_id=?").param(patientId).update();
        insertAllergies(patientId,ids);
        VersionedWrites.advance(em,patient,previous,() -> patient.version);
    }

    record MedicalPatient(long id, String accountNo, String name, LocalDate birthDate) {}
    record MedicalRecord(MedicalPatient patient, List<Allergy> allergies, LocalDateTime allergyVerifiedAt,
                         List<com.adc.visit.VisitDtos.History> visits) {}

    MedicalRecord medicalRecord(long id) {
        var p = get(id);
        return new MedicalRecord(new MedicalPatient(id,p.accountNo(),p.name(),p.birthDate()),p.allergies(),
            p.allergyVerifiedAt(),visits.history(id));
    }

    private void insertAllergies(long patientId, Set<Long> ids) {
        for (long id : ids) {
            jdbc.sql("INSERT INTO patient_allergy(patient_id, allergy_id) VALUES (?, ?)")
                    .param(patientId).param(id).update();
        }
    }

    private Detail detail(Patient p) {
        var allergies = jdbc.sql("""
                SELECT a.id, a.name FROM allergy a JOIN patient_allergy pa ON a.id = pa.allergy_id
                WHERE pa.patient_id = ? ORDER BY a.id
                """).param(p.id).query(Allergy.class).list();
        return new Detail(p.id, p.accountNo, p.name, p.birthDate, p.billingAddress, p.phone, p.email,
                p.emergencyName, p.emergencyPhone, allergies, p.allergyVerifiedAt, p.version);
    }
}
