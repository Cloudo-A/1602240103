package com.adc.dentist;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

@Entity
class Qualification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    String name;

    record Input(@NotBlank(message="请填写资质名称") @Size(max=100, message="资质名称最多100字") String name) {}
    record View(long id, String name) {}
}
