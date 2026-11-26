package com.adc.visit;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "visit")
class Visit {
    @Id Long id;
    long dentistId;
    LocalDateTime arrivedAt;
    LocalDateTime confirmedAt;
    String problem;
    String specialNeeds;
    String relatedHistory;
    String noTreatmentReason;
    @Version int version;
}
