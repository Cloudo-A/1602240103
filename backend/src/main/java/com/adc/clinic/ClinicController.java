package com.adc.clinic;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/clinics")
class ClinicController {
    private final ClinicService service;

    ClinicController(ClinicService service) {
        this.service = service;
    }

    @GetMapping
    List<Clinic.View> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    Clinic.View get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Clinic.View create(@Valid @RequestBody ClinicInput input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    Clinic.View update(@PathVariable long id, @Valid @RequestBody ClinicInput input) {
        return service.update(id, input);
    }
}
