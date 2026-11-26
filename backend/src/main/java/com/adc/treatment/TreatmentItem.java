package com.adc.treatment;

import jakarta.persistence.*;

@Entity
@Table(name = "treatment_item")
class TreatmentItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String name;
    @Column(name = "standard_unit")
    String standardUnit;
    @Column(name = "quantity_scale")
    short quantityScale;
    boolean active = true;
    @Version
    int version;

    protected TreatmentItem() {}

    void setDetails(String name, String standardUnit, short quantityScale) {
        this.name = name.strip();
        this.standardUnit = standardUnit.strip();
        this.quantityScale = quantityScale;
    }
}
