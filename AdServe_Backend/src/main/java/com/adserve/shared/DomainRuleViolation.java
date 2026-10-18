package com.adserve.shared;

/** Base type for every business-rule failure. The API layer maps subclasses to problem-details codes. */
public abstract class DomainRuleViolation extends RuntimeException {
    protected DomainRuleViolation(String message) {
        super(message);
    }
}
