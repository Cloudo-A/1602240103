package com.adc.patient;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "patient")
class Patient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(updatable = false)
    String accountNo;
    String name;
    LocalDate birthDate;
    String billingAddress;
    String phone;
    String email;
    String emergencyName;
    String emergencyPhone;
    LocalDateTime allergyVerifiedAt;
    @Version int version;
}
