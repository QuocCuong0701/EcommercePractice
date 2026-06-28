package com.ecommercebe.exception;

public class DuplicateOrderException extends RuntimeException {
    public DuplicateOrderException(String message) {
        super("Duplicate order: " + message);
    }
}