package com.ceos24.springboot.auth.dto;

public record SignupRequest(
        String name,
        String email,
        String password,
        String phone
) {
}
