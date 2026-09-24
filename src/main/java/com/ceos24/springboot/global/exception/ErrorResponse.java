package com.ceos24.springboot.global.exception;

import org.springframework.http.HttpStatus;

// 클라이언트에게 반환할 JSON 형태
public record ErrorResponse(
        int status,
        String message
) {

//    정적 팩토리 메서드
    public static ErrorResponse of(
            HttpStatus status,
            String message
    ) {
        return new ErrorResponse(
                status.value(),
                message
        );
    }
}