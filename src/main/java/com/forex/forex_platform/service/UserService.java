package com.forex.forex_platform.service;

import com.forex.forex_platform.dto.RegisterRequest;
import com.forex.forex_platform.entity.User;

public interface UserService {

    /** Creates a user; throws ConflictException if the email is already taken. */
    User registerUser(RegisterRequest request);

    /** Loads a user by email; throws ResourceNotFoundException if absent. */
    User getByEmail(String email);

    /** Canonical form used for storing and looking up emails. */
    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
