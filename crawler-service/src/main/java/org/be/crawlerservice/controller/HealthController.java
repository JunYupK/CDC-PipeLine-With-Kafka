package org.be.crawlerservice.controller;

import lombok.RequiredArgsConstructor;
import org.be.crawlerservice.service.health.HealthCheckService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthCheckService healthCheckService;

    /**
     * 기본 헬스체크
     * GET /health
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "timestamp", java.time.Instant.now().toString()
        ));
    }

    /**
     * 상세 헬스체크 (의존성 포함)
     * GET /health/detailed
     */
    @GetMapping("/detailed")
    public ResponseEntity<Map<String, Object>> detailedHealth() {
        Map<String, Object> health = healthCheckService.getDetailedHealth();

        boolean allHealthy = health.values().stream()
                .allMatch(status -> "healthy".equals(status.toString()));

        if (allHealthy) {
            return ResponseEntity.ok(health);
        } else {
            return ResponseEntity.status(503).body(health);
        }
    }

    /**
     * 데이터베이스 연결 상태
     * GET /health/db
     */
    @GetMapping("/db")
    public ResponseEntity<Map<String, String>> databaseHealth() {
        try {
            healthCheckService.checkDatabaseConnection();
            return ResponseEntity.ok(Map.of(
                    "database", "healthy",
                    "message", "Database connection successful"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                    "database", "unhealthy",
                    "message", "Database connection failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Redis 연결 상태
     * GET /health/redis
     */
    @GetMapping("/redis")
    public ResponseEntity<Map<String, String>> redisHealth() {
        try {
            healthCheckService.checkRedisConnection();
            return ResponseEntity.ok(Map.of(
                    "redis", "healthy",
                    "message", "Redis connection successful"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                    "redis", "unhealthy",
                    "message", "Redis connection failed: " + e.getMessage()
            ));
        }
    }
}