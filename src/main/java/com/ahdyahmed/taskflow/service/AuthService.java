package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.Role;
import com.ahdyahmed.taskflow.dto.request.RegisterRequest;
import com.ahdyahmed.taskflow.dto.response.UserResponse;
import com.ahdyahmed.taskflow.exception.EmailAlreadyInUseException;
import com.ahdyahmed.taskflow.mapper.UserMapper;
import com.ahdyahmed.taskflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        // Confirming the email is already taken is normal signup UX (unlike
        // login on Day 6, which will deliberately avoid revealing which
        // half of a credential pair was wrong).
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyInUseException("An account with this email already exists");
        }

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                // TEMPORARY: true until Day 12 wires up email verification.
                // From that point on, new accounts start disabled and this
                // default flips to false.
                .enabled(true)
                .build();

        return userMapper.toResponse(userRepository.save(user));
    }
}
