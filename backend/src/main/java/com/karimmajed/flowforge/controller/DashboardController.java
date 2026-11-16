package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.DashboardOverviewResponse;
import com.karimmajed.flowforge.service.DashboardReadService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardReadService reads;

    public DashboardController(DashboardReadService reads) {
        this.reads = reads;
    }

    @GetMapping("/overview")
    public DashboardOverviewResponse overview() {
        return reads.overview();
    }
}
