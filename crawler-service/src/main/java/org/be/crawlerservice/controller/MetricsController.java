package org.be.crawlerservice.controller;

import lombok.RequiredArgsConstructor;
import org.be.crawlerservice.dto.response.MetricsResponseDto;
import org.be.crawlerservice.metrics.CrawlerMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final CrawlerMetrics crawlerMetrics;
}