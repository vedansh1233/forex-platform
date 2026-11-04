package com.forex.forex_platform.controller;

import com.forex.forex_platform.dto.UserResponse;
import com.forex.forex_platform.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "User")
public class UserController {

    private final UserService userService;

    /** The signed-in user's profile; the frontend calls this to restore a session from a stored token. */
    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        return UserResponse.from(userService.getByEmail(authentication.getName()));
    }
}
