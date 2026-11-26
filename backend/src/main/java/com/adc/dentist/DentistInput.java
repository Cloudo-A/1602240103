package com.adc.dentist;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

record DentistInput(
    @NotBlank(message="请填写员工编号") @Size(max=30, message="员工编号最多30字") String employeeNo,
    @NotBlank(message="请填写姓名") @Size(max=60, message="姓名最多60字") String name,
    @NotNull(message="请填写出生日期") LocalDate birthDate,
    @NotBlank(message="请填写家庭地址") @Size(max=255, message="家庭地址最多255字") String homeAddress,
    @NotBlank(message="请填写电话") @Size(max=30, message="电话最多30字") String phone,
    @NotBlank(message="请填写邮箱") @Size(max=254, message="邮箱最多254字") @Email(message="邮箱格式不正确") String email,
    Boolean active, Integer version,
    @NotNull(message="请提供资质列表") List<@NotNull(message="请填写资质") @Valid Holding> qualifications
) {
    record Holding(@NotNull(message="请选择资质") Long qualificationId,
                   @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss") LocalDateTime verifiedAt) {}
}
