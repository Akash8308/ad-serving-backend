package com.adserve.shared;

/** A value or configuration violates a domain invariant (maps to 400 VALIDATION_FAILED). */
public final class ValidationException extends DomainRuleViolation {
    public ValidationException(String message) {
        super(message);
    }
}
