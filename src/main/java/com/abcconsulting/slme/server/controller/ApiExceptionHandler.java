package com.abcconsulting.slme.server.controller;

import com.abcconsulting.slme.server.photos.PhotoTooLargeException;
import com.abcconsulting.slme.server.service.InUseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(InUseException.class)
    public ResponseEntity<ApiError> handleConflict(InUseException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(PhotoTooLargeException.class)
    public ResponseEntity<ApiError> handleTooLarge(PhotoTooLargeException exception) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(new ApiError(exception.getMessage()));
    }

    public record ApiError(String message) {
    }
}