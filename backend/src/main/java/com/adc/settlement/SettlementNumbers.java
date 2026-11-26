
package com.adc.settlement;

import com.adc.common.BusinessException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SettlementNumbers {
    private final JdbcClient jdbc;

    SettlementNumbers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(LocalDate day) {
        // Keep the date's counter locked until its slip and audit record commit.
        jdbc.sql("""
            INSERT INTO settlement_sequence (issue_date,last_number) VALUES (?,0)
            ON DUPLICATE KEY UPDATE last_number=last_number
            """).param(day).update();
        int number = jdbc.sql("SELECT last_number FROM settlement_sequence WHERE issue_date=? FOR UPDATE")
            .param(day).query(Integer.class).single() + 1;
        if (number > 9999) throw BusinessException.stateNotAllowed("今天的结算单号已用完");
        jdbc.sql("UPDATE settlement_sequence SET last_number=? WHERE issue_date=?").params(number, day).update();
        return "JS" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format(Locale.ROOT, "%04d", number);
    }
}
