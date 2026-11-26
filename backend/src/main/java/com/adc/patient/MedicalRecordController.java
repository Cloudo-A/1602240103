package com.adc.patient;

import org.springframework.web.bind.annotation.*;

@RestController
class MedicalRecordController {
    private final PatientService patients;

    MedicalRecordController(PatientService patients) { this.patients = patients; }

    @GetMapping("/api/patients/{id}/medical-record")
    PatientService.MedicalRecord medicalRecord(@PathVariable long id) {
        return patients.medicalRecord(id);
    }
}
