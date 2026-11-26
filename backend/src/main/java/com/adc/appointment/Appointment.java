package com.adc.appointment;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
class Appointment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    Long patientId;
    Long dentistId;
    Long clinicId;
    LocalDateTime scheduledAt;
    boolean arrived;
    LocalDateTime cancelledAt;
    String cancelledBy;
    @Version int version;
}
