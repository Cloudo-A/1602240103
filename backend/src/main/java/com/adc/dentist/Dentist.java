package com.adc.dentist;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

@Entity
class Dentist {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    String employeeNo;
    String name;
    LocalDate birthDate;
    String homeAddress;
    String phone;
    String email;
    boolean active;
    @Version int version;

    void apply(DentistInput input) {
        employeeNo = input.employeeNo().strip();
        name = input.name().strip();
        birthDate = input.birthDate();
        homeAddress = input.homeAddress().strip();
        phone = input.phone().strip();
        email = input.email().strip();
        active = input.active() == null || input.active();
    }

    record Holding(long qualificationId, String name,
                   @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss") LocalDateTime verifiedAt) {}

    record View(long id, String employeeNo, String name, LocalDate birthDate, String homeAddress,
                String phone, String email, boolean active, int version, List<Holding> qualifications) {}

    View view(List<Holding> qualifications) {
        return new View(id, employeeNo, name, birthDate, homeAddress, phone, email, active, version, qualifications);
    }
}
