package com.adc.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthService service;
    private final UserAccountService accounts;

    AuthController(AuthService service, UserAccountService accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    record LoginRequest(@NotBlank(message = "用户名不能为空") String username,
                        @NotEmpty(message = "密码不能为空") String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }

    @PostMapping("/login")
    AuthService.LoginResult login(@Valid @RequestBody LoginRequest request) {
        return service.login(request.username(), request.password());
    }

    @GetMapping("/me")
    CurrentUser me(@AuthenticationPrincipal CurrentUser user) {
        return user;
    }

    @PostMapping("/logout")
    void logout(HttpServletRequest request, @AuthenticationPrincipal CurrentUser user) {
        if (!service.revoke(user.id(),BearerFilter.token(request))) {
            throw new BadCredentialsException("令牌无效或已过期");
        }
    }

    @PostMapping("/change-password")
    UserAccountDtos.Changed change(HttpServletRequest request, @AuthenticationPrincipal CurrentUser user,
                                   @RequestBody UserAccountDtos.Change input) {
        return accounts.change(user,BearerFilter.token(request),input);
    }
}
