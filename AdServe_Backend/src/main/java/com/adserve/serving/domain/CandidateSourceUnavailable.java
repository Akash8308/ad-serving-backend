package com.adserve.serving.domain;

import com.adserve.shared.DomainRuleViolation;

public final class CandidateSourceUnavailable extends DomainRuleViolation {
    public CandidateSourceUnavailable(String message) {
        super(message);
    }
}
