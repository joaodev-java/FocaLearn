package com.focaAI.learningService.infraestructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class HandlerException {


    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> erro(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("erro", "O assistente está ocupado. Tente novamente em instantes."));
    }

}
