package com.adc.auth;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.adc.auth.UserAccountDtos.*;

@RestController
@RequestMapping("/api/users")
class UserAccountController {
    private final UserAccountService accounts;

    UserAccountController(UserAccountService accounts) { this.accounts = accounts; }

    @GetMapping
    List<Account> list() { return accounts.list(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Account create(@Valid @RequestBody Create input) { return accounts.create(input); }

    @PutMapping("/{id}")
    Account update(@PathVariable long id, @Valid @RequestBody Edit input, @AuthenticationPrincipal CurrentUser user) {
        return accounts.update(id,input,user);
    }

    @PostMapping("/{id}/reset-password")
    Account reset(@PathVariable long id, @RequestBody Reset input) { return accounts.reset(id,input); }
}
