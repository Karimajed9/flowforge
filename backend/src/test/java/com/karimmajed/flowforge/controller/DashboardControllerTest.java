package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.DashboardOverviewResponse;
import com.karimmajed.flowforge.security.ResourceOwner;
import com.karimmajed.flowforge.service.DashboardReadService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardReadService reads;

    @Test
    void overviewReturnsAggregateCountsAndANullableRate() throws Exception {
        when(reads.overview(any(ResourceOwner.class))).thenReturn(new DashboardOverviewResponse(
                2, 1, 5, 1, 1, 0.5, 7));

        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWorkflows").value(2))
                .andExpect(jsonPath("$.activeWorkflows").value(1))
                .andExpect(jsonPath("$.totalExecutions").value(5))
                .andExpect(jsonPath("$.succeededExecutions").value(1))
                .andExpect(jsonPath("$.failedExecutions").value(1))
                .andExpect(jsonPath("$.successRate").value(0.5))
                .andExpect(jsonPath("$.windowDays").value(7));
    }

    @Test
    void overviewKeepsSuccessRateNullWhenNothingFinished() throws Exception {
        when(reads.overview(any(ResourceOwner.class))).thenReturn(new DashboardOverviewResponse(
                0, 0, 0, 0, 0, null, 7));

        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWorkflows").value(0))
                .andExpect(jsonPath("$.successRate").value(nullValue()))
                .andExpect(jsonPath("$.windowDays").value(7));
    }
}
