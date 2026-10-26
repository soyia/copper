package com.soyia.copperv2.security;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
