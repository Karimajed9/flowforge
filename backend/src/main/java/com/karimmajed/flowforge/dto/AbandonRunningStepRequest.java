package com.karimmajed.flowforge.dto;

public record AbandonRunningStepRequest(
        Integer attemptNumber,
        String reason
) {}
