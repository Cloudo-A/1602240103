
package db.demo;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import tools.jackson.databind.json.JsonMapper;

public class V15__demo_rolling_patients extends BaseJavaMigration {
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 7, 1);
    private static final String DEMO_HASH = "$2a$12$nY2u75Q0zOAZ8GlmKHzYXuZ13xVgB3MCBhJrjRvJSqRuO8aJJ0VMu";
    private static final List<String> NAMES = List.of(
        "何雨晴", "许家宁", "梁文博", "罗可欣", "邓思远", "黄淑芬", "蔡立新", "方子墨", "宋怡然", "郑鹏飞", "林若溪",
        "谢志华", "蒋晓曼", "朱梓涵", "叶明哲", "莫春梅", "龚子轩", "吴佳怡", "冯国平", "曾月华", "邱睿", "陆嘉言");
    private static final List<String> ADDRESSES = List.of(
        "广州市天河区中山大道西 102 号", "广州市越秀区东风东路 218 号", "广州市海珠区江南大道中 168 号",
        "深圳市福田区深南大道 3018 号", "深圳市南山区科技南路 19 号", "深圳市罗湖区东门中路 2012 号");

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean ownTransaction = connection.getAutoCommit();
        if (ownTransaction) connection.setAutoCommit(false);
        try {
            seed(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
            if (ownTransaction) connection.commit();
        } catch (RuntimeException | SQLException ex) {
            if (ownTransaction) connection.rollback();
            throw ex;
        } finally {
            if (ownTransaction) connection.setAutoCommit(true);
        }
    }

    private void seed(JdbcClient jdbc) {
        int last = jdbc.sql("SELECT last_number FROM patient_number WHERE singleton=1 FOR UPDATE").query(Integer.class).single();
        int next = Math.max(last, jdbc.sql("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(account_no,2) AS UNSIGNED)),0)
            FROM patient WHERE account_no REGEXP '^P[0-9]{6}$'
            """).query(Integer.class).single());
        var patients = new ArrayList<Long>();
        var accounts = new ArrayList<Long>();
        var allergies = jdbc.sql("SELECT id FROM allergy WHERE name IN ('青霉素','利多卡因','乳胶','碘伏') ORDER BY id")
            .query(Long.class).list();
        int phoneSuffix = 0;
        for (int i = 0; i < NAMES.size(); i++) {
            String phone;
            do {
                phone = String.format(Locale.ROOT, "13926%06d", ++phoneSuffix);
            } while (jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM patient WHERE phone=?)
                    OR EXISTS(SELECT 1 FROM patient_account WHERE phone=?)
                """).params(phone, phone).query(Boolean.class).single());
            String name = NAMES.get(i);
            var verified = i % 6 == 0 ? FIRST_DAY.atTime(8, 30) : null;
            var birth = LocalDate.of(1960 + (i * 13) % 53, 1 + i % 12, 2 + (i * 3) % 26);
            long id = insert(jdbc.sql("""
                INSERT INTO patient(account_no,name,birth_date,billing_address,phone,emergency_name,emergency_phone,allergy_verified_at)
                VALUES (?,?,?,?,?,?,?,?)
                """).params(String.format(Locale.ROOT, "P%06d", ++next), name, birth, ADDRESSES.get(i % ADDRESSES.size()),
                    phone, name.substring(0, 1) + "华", String.format(Locale.ROOT, "13928%06d", i + 1), verified));
            patients.add(id);
            if (i % 3 == 0 && !allergies.isEmpty()) {
                addAllergy(jdbc, id, allergies.get(i % allergies.size()));
                if (i % 2 == 0 && allergies.size() > 1) addAllergy(jdbc, id, allergies.get((i + 1) % allergies.size()));
            }
            if (i < 4) {
                accounts.add(insert(jdbc.sql("""
                    INSERT INTO patient_account(phone,name,password_hash,created_at) VALUES (?,?,?,?)
                    """).params(phone, name, DEMO_HASH, FIRST_DAY.atStartOfDay())));
            }
        }
        jdbc.sql("UPDATE patient_number SET last_number=? WHERE singleton=1").param(next).update();
        dentist(jdbc, "D003", "dentist3");
        dentist(jdbc, "D004", "dentist4");
        byte[] roster = JsonMapper.builder().build().writeValueAsBytes(List.of(patients, accounts));
        jdbc.sql("""
            INSERT INTO request_record(account_type,request_id,user_id,action,content_hash,created_at,response_body)
            VALUES ('EMPLOYEE','demo-rolling:roster',0,'DEMO_ROLLING','',?,?)
            """).params(FIRST_DAY.atStartOfDay(), roster).update();
    }

    private static void dentist(JdbcClient jdbc, String employee, String username) {
        jdbc.sql("""
            INSERT INTO app_user(username,password_hash,display_name,role,dentist_id,active)
            SELECT ?,?,d.name,'DENTIST',d.id,TRUE FROM dentist d
            WHERE d.employee_no=? AND NOT EXISTS (
                SELECT 1 FROM app_user u WHERE u.username=? OR u.dentist_id=d.id
            )
            """).params(username, DEMO_HASH, employee, username).update();
    }

    private static void addAllergy(JdbcClient jdbc, long patient, long allergy) {
        jdbc.sql("INSERT INTO patient_allergy(patient_id,allergy_id) VALUES (?,?)").params(patient, allergy).update();
    }

    private static long insert(JdbcClient.StatementSpec statement) {
        var key = new GeneratedKeyHolder();
        statement.update(key, "id");
        return Objects.requireNonNull(key.getKey()).longValue();
    }
}
