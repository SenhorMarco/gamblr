package com.gamblr.writer;

import org.springframework.http.HttpStatus;

public class ApiError extends RuntimeException {
    final HttpStatus status;
    final String code;
    ApiError(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; }
    static ApiError bad(String code, String message) { return new ApiError(HttpStatus.BAD_REQUEST, code, message); }
    static ApiError forbidden() { return new ApiError(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Acesso nao permitido."); }
    static ApiError missing() { return new ApiError(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recurso nao encontrado."); }
    static ApiError conflict(String code, String message) { return new ApiError(HttpStatus.CONFLICT, code, message); }
}
