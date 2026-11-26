package com.adc.visit;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class VisitDtos {
    private VisitDtos() {}

    public record Named(long id, String name) {}
    public record PatientRef(long id, String accountNo, String name) {}
    public record PatientInfo(long id, String accountNo, String name, List<Named> allergies, LocalDateTime allergyVerifiedAt) {}
    public record PriceLine(long itemId, String itemName, String unitName, BigDecimal quantity,
                            BigDecimal unitPrice, BigDecimal subtotal, LocalDate priceEffectiveFrom) {}
    public record Summary(long id, String status, LocalDateTime arrivedAt, PatientRef patient,
                          Named clinic, Named dentist, BigDecimal total) {}
    public record Detail(long id, String status, LocalDateTime arrivedAt, LocalDateTime confirmedAt,
                         PatientInfo patient, Named clinic, Named dentist, List<Named> intendedItems,
                         String problem, String specialNeeds, String relatedHistory, String noTreatmentReason,
                         List<PriceLine> treatments, BigDecimal total, String settlementNo, int version) {}
    record Confirm(String problem, String specialNeeds, String relatedHistory, JsonNode allergyIds,
                   JsonNode treatments, Integer version) {}
    record End(String reason, String problem, Integer version) {}
    record Preview(List<PriceLine> lines, List<String> problems) {}
    public record HistoryLine(String itemName, String unitName, BigDecimal quantity, BigDecimal unitPrice, BigDecimal subtotal) {}
    public record History(long visitId, LocalDateTime arrivedAt, String status, Named clinic, Named dentist,
                          String problem, String specialNeeds, String relatedHistory, String noTreatmentReason,
                          List<HistoryLine> treatments) {}
}
