package com.forex.forex_platform.service;

import com.forex.forex_platform.dto.AuthResponse;
import com.forex.forex_platform.dto.LoginRequest;
import com.forex.forex_platform.dto.RegisterRequest;
import com.forex.forex_platform.dto.UserResponse;
import com.forex.forex_platform.entity.User;
import com.forex.forex_platform.exception.InvalidCredentialsException;
import com.forex.forex_platform.repository.UserRepository;
import com.forex.forex_platform.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /** Registers the user and signs them in straight away. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User user = userService.registerUser(request);
        return issueToken(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(UserService.normalizeEmail(request.email()))
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }

        return issueToken(user);
    }

    private AuthResponse issueToken(User user) {
        String token = jwtService.generateToken(user.getEmail());
        return AuthResponse.bearer(token, jwtService.getExpirationSeconds(), UserResponse.from(user));
    }
}
