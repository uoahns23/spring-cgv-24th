package com.ceos24.springboot.auth.service;

import com.ceos24.springboot.auth.dto.SignupRequest;
import com.ceos24.springboot.auth.dto.SignupResponse;
import com.ceos24.springboot.global.exception.ErrorCode;
import com.ceos24.springboot.global.exception.GlobalException;
import com.ceos24.springboot.user.domain.Role;
import com.ceos24.springboot.user.domain.User;
import com.ceos24.springboot.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Transactional
public class SignupService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SignupResponse signupUser(SignupRequest request) {
        return signup(request, Role.USER);
    }

    public SignupResponse signupAdmin(SignupRequest request) {
        return signup(request, Role.ADMIN);
    }

    private SignupResponse signup(SignupRequest request, Role role) {
        if (userRepository.existsByEmail(request.email())) {
            throw new GlobalException(ErrorCode.DUPLICATE_EMAIL);
        }

        // 비밀번호를 해시 처리하여 원문 대신 저장
        String encodedPassword =
                passwordEncoder.encode(request.password());

        User user = switch (role) {
            case USER -> User.create(
                    request.name(),
                    request.email(),
                    encodedPassword,
                    request.phone()
            );
            case ADMIN -> User.createAdmin(
                    request.name(),
                    request.email(),
                    encodedPassword,
                    request.phone()
            );
        };

        User savedUser = userRepository.saveAndFlush(user);
        return SignupResponse.from(savedUser);
    }
}
