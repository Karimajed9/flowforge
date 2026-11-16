package com.karimmajed.flowforge.dto;

public record DashboardOverviewResponse(
        long totalWorkflows,
        long activeWorkflows,
        long totalExecutions,
        long succeededExecutions,
        long failedExecutions,
        Double successRate,
        int windowDays
) {
}
