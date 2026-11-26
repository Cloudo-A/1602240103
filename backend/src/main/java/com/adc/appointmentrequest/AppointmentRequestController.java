package com.adc.appointmentrequest;

import com.adc.auth.CurrentUser;
import com.adc.auth.PatientPrincipal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.adc.appointmentrequest.AppointmentRequestDtos.*;

@RestController
@RequestMapping("/api")
class AppointmentRequestController {
    private final AppointmentRequestService service;
    private final AppointmentRequestQueries queries;

    AppointmentRequestController(AppointmentRequestService service, AppointmentRequestQueries queries) {
        this.service = service;
        this.queries = queries;
    }

    @GetMapping("/patient/appointment-requests")
    List<View> mine(@AuthenticationPrincipal PatientPrincipal patient) { return queries.mine(patient.id()); }

    @PostMapping("/patient/appointment-requests") @ResponseStatus(HttpStatus.CREATED)
    View create(@RequestBody Create body, @AuthenticationPrincipal PatientPrincipal patient) {
        return service.create(body,patient);
    }

    @PostMapping("/patient/appointment-requests/{id}/withdraw")
    View withdraw(@PathVariable long id, @AuthenticationPrincipal PatientPrincipal patient) {
        return service.withdraw(id,patient.id());
    }

    @GetMapping("/appointment-requests")
    List<View> list(@RequestParam(required=false) List<String> status,@RequestParam(required=false) Long clinicId,
                    @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        return queries.list(status,clinicId,from,to);
    }

    @GetMapping("/appointment-requests/{id}")
    View detail(@PathVariable long id) { return queries.get(id); }

    @PostMapping("/appointment-requests/{id}/invalidate")
    View invalidate(@PathVariable long id,@RequestBody Invalidate body,@AuthenticationPrincipal CurrentUser user) {
        return service.invalidate(id,body,user);
    }
}
