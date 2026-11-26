package com.adc.dentist;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class DentistController {
    private final DentistService service;

    DentistController(DentistService service) {
        this.service = service;
    }

    @GetMapping("/dentists")
    List<Dentist.View> list() {
        return service.list();
    }

    @GetMapping("/dentists/{id}")
    Dentist.View get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/dentists") @ResponseStatus(HttpStatus.CREATED)
    Dentist.View create(@Valid @RequestBody DentistInput input) {
        return service.create(input);
    }

    @PutMapping("/dentists/{id}")
    Dentist.View update(@PathVariable long id, @Valid @RequestBody DentistInput input) {
        return service.update(id, input);
    }

    @PostMapping("/qualifications") @ResponseStatus(HttpStatus.CREATED)
    Qualification.View createQualification(@Valid @RequestBody Qualification.Input input) {
        return service.createQualification(input);
    }
}
