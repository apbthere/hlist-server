package com.abcconsulting.slme.server.service;

/** Thrown when a shared catalog entry (department or brand) can't be deleted because items still use it. */
public class InUseException extends RuntimeException {

    public InUseException(String message) {
        super(message);
    }
}
