package com.akash.reporting.controller;

import com.akash.reporting.dto.CreateReportRequest;
import com.akash.reporting.dto.ReportResponse;
import com.akash.reporting.service.ReportService;
import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ReportResponse> create(@Valid @RequestBody CreateReportRequest request) {
        var response = ReportResponse.from(service.create(request));
        return ResponseEntity.accepted()
                .location(URI.create(response.statusUrl()))
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<ReportResponse> get(@PathVariable UUID reportId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ReportResponse.from(service.get(reportId)));
    }
}
