package com.forex.forex_platform.service.impl;

import com.forex.forex_platform.dto.RegisterRequest;
import com.forex.forex_platform.entity.User;
import com.forex.forex_platform.exception.ConflictException;
import com.forex.forex_platform.exception.ResourceNotFoundException;
import com.forex.forex_platform.repository.UserRepository;
import com.forex.forex_platform.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public User registerUser(RegisterRequest request) {

        String email = UserService.normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }

        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.password()));

        return userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return userRepository.findByEmail(UserService.normalizeEmail(email))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
