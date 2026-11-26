package com.adc.clinic;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

record ClinicInput(
    @NotBlank(message="请填写诊所名称") @Size(max=60, message="诊所名称最多60字") String name,
    @NotBlank(message="请填写邮政地址") @Size(max=255, message="邮政地址最多255字") String postalAddress,
    @NotBlank(message="请填写主管姓名") @Size(max=60, message="主管姓名最多60字") String managerName,
    @NotBlank(message="请填写电话") @Size(max=30, message="电话最多30字") String phone,
    @NotBlank(message="请填写邮箱") @Size(max=254, message="邮箱最多254字") @Email(message="邮箱格式不正确") String email,
    @NotNull(message="请填写办公面积") @Positive(message="办公面积必须大于零")
    @Digits(integer=10, fraction=2, message="办公面积最多10位整数和2位小数") BigDecimal officeArea,
    Boolean active, Integer version
) {}
