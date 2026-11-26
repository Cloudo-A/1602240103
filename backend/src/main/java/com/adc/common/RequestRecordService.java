package com.adc.common;

import java.sql.*;
import java.time.Clock;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import java.util.function.Supplier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RequestRecordService {
    enum AccountType { EMPLOYEE, PATIENT }
    record Owner(AccountType type, long id) {}
    record Result(int status, String type, byte[] body) {}
    private record Stored(long user, String hash, Result result) {}

    private final DataSource locks;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final BCryptPasswordEncoder sensitiveFingerprints = new BCryptPasswordEncoder();

    RequestRecordService(DataSourceProperties properties, JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        // A physical session holds the key across business rollback and recording the first error.
        // It does not borrow a second connection from the business transaction pool.
        this.locks = properties.initializeDataSourceBuilder().type(SimpleDriverDataSource.class).build();
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    Result execute(String id, Owner owner, String action, String digest, boolean sensitive,
                   Supplier<Result> dispatch) throws SQLException {
        try (Connection lock = locks.getConnection();
             var acquire = lock.prepareStatement("SELECT GET_LOCK(SHA2(CONCAT(DATABASE(), ':', ?, ':', ?),256),30)")) {
            acquire.setString(1, owner.type().name());
            acquire.setString(2, id);
            try (var row = acquire.executeQuery()) {
                if (!row.next() || row.getInt(1) != 1) throw new SQLException("请求标识锁等待超时");
            }
            var previous = jdbc.sql("""
                SELECT user_id,content_hash,response_status,response_type,response_body
                FROM request_record WHERE account_type=? AND request_id=?
                """).params(owner.type().name(), id).query((rs,n) -> new Stored(rs.getLong(1), rs.getString(2),
                    new Result(rs.getInt(3),rs.getString(4),rs.getBytes(5)))).optional();
            if (previous.isPresent()) {
                var entry = previous.get();
                if (entry.user != owner.id() || entry.result.status == 0 || !matches(digest, entry.hash, sensitive))
                    throw BusinessException.duplicate();
                return entry.result;
            }
            // A fast digest would allow offline guessing of a password in an otherwise known request.
            String hash = sensitive ? sensitiveFingerprints.encode(digest) : digest;
            var first = transaction.execute(tx -> {
                var result = dispatch.get();
                if (result.status >= 400) tx.setRollbackOnly();
                else save(id, owner, action, hash, result);
                return result;
            });
            if (first.status >= 400) save(id, owner, action, hash, first);
            return first;
        }
    }

    private boolean matches(String digest, String stored, boolean sensitive) {
        if (!sensitive) return stored.equals(digest);
        return stored.startsWith("$2") && sensitiveFingerprints.matches(digest, stored);
    }

    private void save(String id, Owner owner, String action, String hash, Result result) {
        jdbc.sql("""
            INSERT INTO request_record
            (account_type,request_id,user_id,action,content_hash,created_at,response_status,response_type,response_body)
            VALUES (?,?,?,?,?,?,?,?,?)
            """).params(owner.type().name(),id,owner.id(),action,hash,LocalDateTime.now(clock).withNano(0),
                result.status,result.type,result.body).update();
    }
}
