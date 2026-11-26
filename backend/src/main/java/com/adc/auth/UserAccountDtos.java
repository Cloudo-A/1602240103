package com.adc.auth;

import jakarta.validation.constraints.*;

final class UserAccountDtos {
    private UserAccountDtos() {}

    record Dentist(long id, String employeeNo, String name) {}
    record Account(long id, String username, String displayName, String role, Dentist dentist, boolean active, int version) {}
    record Create(
        @NotNull(message="请填写登录名") @Pattern(regexp="[A-Za-z0-9_]{3,40}", message="登录名为 3—40 位字母、数字或下划线") String username,
        @NotBlank(message="请填写显示姓名") @Size(max=60, message="显示姓名最多60个字符") String displayName,
        @NotNull(message="请选择岗位") @Pattern(regexp="FRONT_DESK|DENTIST|HQ", message="请选择有效岗位") String role,
        Long dentistId, String password
    ) {
        @Override public String toString() { return "Create[redacted]"; }
    }
    record Edit(@NotBlank(message="请填写显示姓名") @Size(max=60, message="显示姓名最多60个字符") String displayName,
                Long dentistId, @NotNull(message="请选择使用状态") Boolean active, Integer version) {}
    record Reset(String newPassword) {
        @Override public String toString() { return "Reset[redacted]"; }
    }
    record Change(String oldPassword, String newPassword) {
        @Override public String toString() { return "Change[redacted]"; }
    }
    record Changed(boolean ok) {}
}
