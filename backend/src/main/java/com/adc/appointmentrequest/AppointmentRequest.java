package com.adc.appointmentrequest;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
class AppointmentRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    long patientAccountId;
    String contactName;
    String contactPhone;
    long clinicId;
    LocalDate preferredDate;
    String timeSlot;
    String note;
    String status;
    LocalDateTime createdAt;
    LocalDateTime handledAt;
    Long handledByUserId;
    String handledBy;
    String invalidReason;
    LocalDateTime withdrawnAt;
    Long appointmentId;
    @Version int version;
}
