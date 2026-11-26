package com.adc.clinic;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
class Clinic {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    String name;
    String postalAddress;
    String managerName;
    String phone;
    String email;
    BigDecimal officeArea;
    boolean active;
    @Version int version;

    void apply(ClinicInput input) {
        name = input.name().strip();
        postalAddress = input.postalAddress().strip();
        managerName = input.managerName().strip();
        phone = input.phone().strip();
        email = input.email().strip();
        officeArea = input.officeArea().setScale(2);
        active = input.active() == null || input.active();
    }

    record View(long id, String name, String postalAddress, String managerName, String phone,
                String email, BigDecimal officeArea, boolean active, int version) {}

    View view() {
        return new View(id, name, postalAddress, managerName, phone, email, officeArea, active, version);
    }
}
