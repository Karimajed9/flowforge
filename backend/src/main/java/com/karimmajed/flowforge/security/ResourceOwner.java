package com.karimmajed.flowforge.security;

/**
 * The caller who owns a workflow. Both values come from the validated
 * access token. The issuer is part of the identity so two issuers cannot
 * share a subject.
 */
public record ResourceOwner(String subject, String issuer) {

    public ResourceOwner {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Owner subject is required");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Owner issuer is required");
        }
    }
}
