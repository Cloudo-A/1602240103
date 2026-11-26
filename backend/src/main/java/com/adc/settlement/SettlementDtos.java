package com.adc.settlement;

import com.adc.visit.VisitDtos.PriceLine;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

final class SettlementDtos {
    private SettlementDtos() {}

    record Patient(String accountNo, String name) {}
    record Name(String name) {}
    record Issue(String checkToken) {}
    record Slip(String settlementNo, long visitId, LocalDateTime issuedAt, String issuedBy,
                Patient patient, Name clinic, Name dentist, List<PriceLine> lines, BigDecimal total) {}
    record Preview(boolean existing, Slip settlement, List<PriceLine> lines, BigDecimal total, String checkToken) {}
    record Issued(Slip settlement, boolean created) {}
}
