package com.adc.appointment;

import com.adc.auth.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/appointments")
class AppointmentController {
    record Booking(Long patientId, Long dentistId, Long clinicId, String scheduledAt, List<Long> itemIds, JsonNode requestId) {}
    record Cancel(Integer version) {}
    record Arrival(String arrivedAt, Long dentistId, Long clinicId, Integer version) {}
    record ArrivalResult(AppointmentView appointment, long visitId) {}
    record Check(boolean ok, List<String> problems) {}

    private final AppointmentQueries queries;
    private final AppointmentService service;

    AppointmentController(AppointmentQueries queries, AppointmentService service) {
        this.queries = queries;
        this.service = service;
    }

    @GetMapping
    List<AppointmentView> list(@RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to,
                    @RequestParam(required=false) Long clinicId, @RequestParam(required=false) Long dentistId,
                    @RequestParam(required=false) Long patientId, @RequestParam(required=false) List<String> status,
                    @AuthenticationPrincipal CurrentUser user) {
        return queries.list(from, to, clinicId, dentistId, patientId, status, user);
    }

    @GetMapping("/{id}")
    AppointmentView get(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        return queries.getForUser(id, user);
    }

    @PostMapping("/check")
    Check check(@RequestBody Booking body) {
        return service.check(body);
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    AppointmentView create(@RequestBody Booking body, @AuthenticationPrincipal CurrentUser user) {
        return service.create(body, user);
    }

    @PostMapping("/{id}/cancel")
    AppointmentView cancel(@PathVariable long id, @RequestBody Cancel body, @AuthenticationPrincipal CurrentUser user) {
        return service.cancel(id, body.version(), user);
    }

    @PostMapping("/{id}/arrive")
    ArrivalResult arrive(@PathVariable long id, @RequestBody Arrival body) {
        return service.arrive(id, body);
    }
}
