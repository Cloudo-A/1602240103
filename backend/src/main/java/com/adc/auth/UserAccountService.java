package com.adc.auth;

import com.adc.common.*;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.adc.auth.UserAccountDtos.*;

@Service
@Transactional
class UserAccountService {
    private final EntityManager em;
    private final JdbcClient jdbc;
    private final PasswordCodec passwords;
    private final AuthService auth;
    private final OperationLogService logs;

    UserAccountService(EntityManager em, JdbcClient jdbc, PasswordCodec passwords, AuthService auth, OperationLogService logs) {
        this.em = em;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.auth = auth;
        this.logs = logs;
    }

    @Transactional(readOnly=true)
    List<Account> list() {
        return jdbc.sql("""
            SELECT u.id,u.username,u.display_name,u.role,u.active,u.version,
                   d.id dentist_id,d.employee_no,d.name dentist_name
            FROM app_user u LEFT JOIN dentist d ON d.id=u.dentist_id ORDER BY u.id
            """).query((r,n) -> {
                Dentist dentist = r.getObject("dentist_id") == null ? null : dentist(r);
                return new Account(r.getLong("id"),r.getString("username"),r.getString("display_name"),
                    r.getString("role"),dentist,r.getBoolean("active"),r.getInt("version"));
            }).list();
    }

    Account create(Create input) {
        var hash = passwords.encode(input.password(), "password");
        var dentist = binding(input.role(),input.dentistId(),null);
        if (jdbc.sql("SELECT id FROM app_user WHERE username=?").param(input.username()).query(Long.class).optional().isPresent())
            throw BusinessException.conflict("登录名已被使用");
        var account = new AppUser();
        account.username = input.username();
        account.displayName = input.displayName().strip();
        account.role = input.role();
        account.active = true;
        account.passwordHash = hash;
        account.dentistId = input.dentistId();
        em.persist(account);
        var result = view(account,dentist);
        logs.record("CREATE_USER","USER",account.id,result);
        return result;
    }

    Account update(long id, Edit input, CurrentUser actor) {
        var account = VersionedWrites.lock(em,AppUser.class,id);
        var before = VersionedWrites.check(input.version(),account.version,() -> current(account));
        if (!input.active() && id == actor.id()) throw BusinessException.stateNotAllowed("不能停用自己正在使用的账号");
        // An inactive profile is still valid for an account retaining its binding.
        var dentist = Objects.equals(account.dentistId, input.dentistId())
            ? before.dentist()
            : binding(account.role, input.dentistId(), id);
        account.displayName = input.displayName().strip();
        account.dentistId = input.dentistId();
        account.active = input.active();
        if (!account.active) auth.revokeAll(id);
        VersionedWrites.advance(em,account,input.version(),() -> account.version);
        var updated = view(account,dentist);
        logs.record("UPDATE_USER","USER",id,Map.of("before",before,"after",updated));
        return updated;
    }

    Account reset(long id, Reset input) {
        var account = VersionedWrites.lock(em,AppUser.class,id);
        int version = account.version;
        account.passwordHash = passwords.encode(input.newPassword(),"newPassword");
        auth.revokeAll(id);
        VersionedWrites.advance(em,account,version,() -> account.version);
        var updated = current(account);
        logs.record("RESET_PASSWORD","USER",id,updated);
        return updated;
    }

    Changed change(CurrentUser actor, String token, Change input) {
        var account = VersionedWrites.lock(em,AppUser.class,actor.id());
        if (!account.active) throw new BadCredentialsException("账号已停用，请联系总部");
        auth.requireCurrentToken(account.id,token);
        if (!passwords.matches(input.oldPassword(),account.passwordHash))
            throw BusinessException.invalid("oldPassword","原密码不正确");
        if (input.oldPassword().equals(input.newPassword()))
            throw BusinessException.invalid("newPassword","新密码不能和原密码相同");
        int version = account.version;
        account.passwordHash = passwords.encode(input.newPassword(),"newPassword");
        auth.revokeOthers(account.id,token);
        VersionedWrites.advance(em,account,version,() -> account.version);
        logs.record("CHANGE_PASSWORD","USER",account.id,current(account));
        return new Changed(true);
    }

    private Dentist binding(String role, Long id, Long accountId) {
        if (!"DENTIST".equals(role)) {
            if (id != null) throw BusinessException.invalid("dentistId","只有牙医岗位才能绑定牙医");
            return null;
        }
        if (id == null) throw BusinessException.invalid("dentistId","请选择这个账号对应的牙医");
        var dentist = jdbc.sql("SELECT id dentist_id,employee_no,name dentist_name,active FROM dentist WHERE id=? FOR UPDATE")
            .param(id).query((r,n) -> {
                if (!r.getBoolean("active")) throw BusinessException.invalid("dentistId","该牙医已停用");
                return dentist(r);
            }).optional().orElseThrow(() -> BusinessException.invalid("dentistId","牙医不存在"));
        var owner = jdbc.sql("SELECT id FROM app_user WHERE dentist_id=?").param(id).query(Long.class).optional();
        if (owner.isPresent() && !owner.get().equals(accountId)) throw BusinessException.conflict("该牙医已经绑定其他账号");
        return dentist;
    }

    private Account current(AppUser account) {
        Dentist dentist = account.dentistId == null ? null : jdbc.sql("""
            SELECT id dentist_id,employee_no,name dentist_name FROM dentist WHERE id=?
            """).param(account.dentistId).query((r,n) -> dentist(r)).single();
        return view(account,dentist);
    }

    private static Dentist dentist(ResultSet rs) throws SQLException {
        return new Dentist(rs.getLong("dentist_id"),rs.getString("employee_no"),rs.getString("dentist_name"));
    }

    private static Account view(AppUser user, Dentist dentist) {
        return new Account(user.id,user.username,user.displayName,user.role,dentist,user.active,user.version);
    }
}
