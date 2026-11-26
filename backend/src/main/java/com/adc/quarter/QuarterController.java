package com.adc.quarter;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import static com.adc.quarter.QuarterCalculation.Period;
import static com.adc.quarter.QuarterDtos.*;

@RestController
@RequestMapping("/api/quarters")
class QuarterController {
    private final QuarterService service;

    QuarterController(QuarterService service) { this.service = service; }

    @GetMapping
    List<Closed> list() { return service.list(); }

    @GetMapping("/{year}/{quarter}")
    Preparation prepare(@PathVariable int year, @PathVariable int quarter) {
        return service.prepare(Period.of(year,quarter));
    }

    @PostMapping("/{year}/{quarter}/preview")
    Preview preview(@PathVariable int year, @PathVariable int quarter, @RequestBody Input input) {
        return service.preview(Period.of(year,quarter),input);
    }

    @PostMapping("/{year}/{quarter}/confirm")
    Closed confirm(@PathVariable int year, @PathVariable int quarter, @RequestBody Input input) {
        return service.confirm(Period.of(year,quarter),input);
    }
}
