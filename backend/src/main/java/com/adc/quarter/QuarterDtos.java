package com.adc.quarter;

import com.adc.visit.VisitDtos.Summary;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

final class QuarterDtos {
    private QuarterDtos() {}

    record Cost(Long dentistId, BigDecimal supplyCost, BigDecimal operatingCost, String basis) {}
    record Input(List<Cost> rows, String fundNote, String checkToken) {}
    record PreparedRow(long dentistId, String employeeNo, String name, BigDecimal revenue,
                       BigDecimal supplyCost, BigDecimal operatingCost, String basis) {}
    record Row(long dentistId, String employeeNo, String name, BigDecimal revenue,
               BigDecimal supplyCost, BigDecimal operatingCost, String basis, BigDecimal netProfit) {}
    record Preparation(int year, int quarter, boolean quarterEnded, Closed existing,
                       List<Summary> unsettledVisits, List<PreparedRow> rows) {}
    record Preview(List<Row> rows, BigDecimal companyNetProfit, BigDecimal incentiveFund, boolean fundNoteRequired,
                   List<String> problems, String checkToken) {}
    record Closed(long id, int year, int quarter, LocalDateTime confirmedAt, LocalDateTime firstConfirmedAt,
                  int collectionWorkdays, BigDecimal companyNetProfit, BigDecimal incentiveFund, String fundStatus,
                  LocalDateTime fundTransferAt, String fundNote, List<Row> rows) {}
}
