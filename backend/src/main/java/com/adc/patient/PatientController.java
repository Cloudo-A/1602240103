package com.adc.patient;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patients")
class PatientController {
    private final PatientService patients;

    PatientController(PatientService patients) {
        this.patients = patients;
    }

    record Form(
        @NotBlank(message = "姓名不能为空") @Size(max = 60, message = "姓名最多60个字符") String name,
        @NotNull(message = "请填写出生日期") @PastOrPresent(message = "出生日期不能晚于今天") LocalDate birthDate,
        @NotBlank(message = "请填写账单地址") @Size(max = 255, message = "账单地址最多255个字符") String billingAddress,
        @NotBlank(message = "电话不能为空") @Size(max = 30, message = "电话最多30个字符") String phone,
        @Email(message = "电子邮箱格式不正确") @Size(max = 254, message = "电子邮箱最多254个字符") String email,
        @NotBlank(message = "请填写紧急联系人姓名") @Size(max = 60, message = "姓名最多60个字符") String emergencyName,
        @NotBlank(message = "请填写紧急联系人电话") @Size(max = 30, message = "电话最多30个字符") String emergencyPhone,
        List<@NotNull(message = "过敏项目编号不能为空") @Positive(message = "过敏项目编号必须为正数") Long> allergyIds,
        Boolean confirmNotDuplicate, Integer version
    ) {}

    record Summary(long id, String accountNo, String name, LocalDate birthDate, String phone) {}
    record Page(List<Summary> items, long total) {}
    record Allergy(long id, String name) {}
    record Detail(long id, String accountNo, String name, LocalDate birthDate, String billingAddress,
                  String phone, String email, String emergencyName, String emergencyPhone,
                  List<Allergy> allergies, LocalDateTime allergyVerifiedAt, int version) {}

    @GetMapping
    Page search(@RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "20") int size) {
        return patients.search(keyword.strip(), page, size);
    }

    @GetMapping("/{id}")
    Detail detail(@PathVariable long id) {
        return patients.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Detail create(@Valid @RequestBody Form form) {
        return patients.create(form);
    }

    @PutMapping("/{id}")
    Detail update(@PathVariable long id, @Valid @RequestBody Form form) {
        return patients.update(id, form);
    }
}
