package com.adc.appointment;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.List;

public record AppointmentView(long id, String status,
        @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss") LocalDateTime scheduledAt,
        Patient patient, Dentist dentist, Named clinic, List<Named> items, boolean arrived,
        @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss") LocalDateTime cancelledAt,
        String cancelledBy, Long visitId, int version) {
    public static String statusOf(LocalDateTime scheduledAt, boolean arrived, LocalDateTime cancelledAt, LocalDateTime now) {
        if (cancelledAt != null) return "CANCELLED";
        if (arrived) return "ARRIVED";
        return scheduledAt.isBefore(now) ? "PENDING_VERIFY" : "VALID";
    }

    public record Patient(long id, String accountNo, String name) {}
    public record Dentist(long id, String employeeNo, String name) {}
    public record Named(long id, String name) {}
}
