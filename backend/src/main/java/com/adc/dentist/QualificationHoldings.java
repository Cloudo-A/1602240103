package com.adc.dentist;

import com.adc.common.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class QualificationHoldings {
    private static final String SELECT = """
        select h.dentist_id, q.id, q.name, h.verified_at
        from qualification_holding h join qualification q on q.id = h.qualification_id
        """;
    private final JdbcClient jdbc;

    QualificationHoldings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private Dentist.Holding holding(ResultSet row) throws SQLException {
        return new Dentist.Holding(row.getLong("id"), row.getString("name"),
            row.getObject("verified_at", LocalDateTime.class));
    }

    List<Dentist.Holding> forDentist(long id) {
        return jdbc.sql(SELECT + " where h.dentist_id = ? order by q.id").param(id)
            .query((row, n) -> holding(row)).list();
    }

    List<Dentist.Holding> currentForUpdate(long id) {
        // A locking read sees the winner's committed holdings even if a request
        // already opened a MySQL repeatable-read snapshot before locking its parent.
        return jdbc.sql(SELECT + " where h.dentist_id = ? order by q.id for share").param(id)
            .query((row, n) -> holding(row)).list();
    }

    Map<Long, List<Dentist.Holding>> all() {
        return jdbc.sql(SELECT + " order by h.dentist_id, q.id").query(rs -> {
            Map<Long, List<Dentist.Holding>> result = new HashMap<>();
            while (rs.next()) {
                result.computeIfAbsent(rs.getLong("dentist_id"), id -> new ArrayList<>()).add(holding(rs));
            }
            return result;
        });
    }

    void replace(long dentistId, List<DentistInput.Holding> holdings) {
        var ids = holdings.stream().map(DentistInput.Holding::qualificationId).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw BusinessException.invalid("qualifications", "同一资质不能重复添加");
        }
        if (!ids.isEmpty()) {
            var count = jdbc.sql("select count(*) from qualification where id in (:ids)").param("ids", ids)
                .query(Long.class).single();
            if (count != ids.size()) throw BusinessException.invalid("qualifications", "包含不存在的资质");
        }
        jdbc.sql("delete from qualification_holding where dentist_id = ?").param(dentistId).update();
        for (var holding : holdings) {
            jdbc.sql("insert into qualification_holding(dentist_id, qualification_id, verified_at) values (?, ?, ?)")
                .param(dentistId).param(holding.qualificationId()).param(3, holding.verifiedAt(), Types.TIMESTAMP).update();
        }
    }
}
