package com.gamblr.writer;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
    record ErrorBody(String code, String message) {}
    @ExceptionHandler(ApiError.class)
    ResponseEntity<ErrorBody> business(ApiError error) {
        return ResponseEntity.status(error.status).body(new ErrorBody(error.code, error.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", "Confira os campos da requisicao."));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> integrity(DataIntegrityViolationException error) {
        return ResponseEntity.status(409).body(new ErrorBody("CONFLICT", "A operacao conflita com o estado atual."));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> database(DataAccessException error) {
        return ResponseEntity.status(503).body(new ErrorBody("DATABASE_UNAVAILABLE", "Nao foi possivel concluir a operacao. Consulte o estado antes de repetir."));
    }
}
