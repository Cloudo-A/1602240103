package com.adc.appointmentrequest;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

final class AppointmentRequestDtos {
    private AppointmentRequestDtos() {}
    record Create(Long clinicId, LocalDate preferredDate, String timeSlot, List<Long> itemIds, String note) {}
    record Invalidate(String reason, Integer version) {}
    record Named(long id, String name) {}
    record Clinic(long id, String name, String phone) {}
    record Booked(long id, String status,
                  @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss") LocalDateTime scheduledAt, Named dentist, Named clinic) {}
    record View(long id, String status, @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
                String contactName, String contactPhone, Clinic clinic, LocalDate preferredDate, String timeSlot,
                List<Named> items, String note, @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss") LocalDateTime handledAt,
                String handledBy, String invalidReason,
                @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss") LocalDateTime withdrawnAt, Booked appointment, int version) {}
}
