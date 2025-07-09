package org.be.crawlerservice.service.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthCheckServiceImpl implements HealthCheckService {

    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)  // RestTemplate이 없을 수도 있으므로 optional로 설정
    private RestTemplate restTemplate;

    @Override
    public Map<String, Object> getDetailedHealth() {
        Map<String, Object> health = new HashMap<>();

        // 기본 정보
        health.put("timestamp", LocalDateTime.now().toString());
        health.put("application", "crawler-service");
        health.put("version", "1.0.0");

        // 각 컴포넌트 헬스체크
        health.put("database", checkComponentHealth(this::checkDatabaseConnection));
        health.put("redis", checkComponentHealth(this::checkRedisConnection));
        health.put("crawl4ai", checkComponentHealth(this::checkCrawl4AIConnection));

        // JVM 정보
        health.put("jvm", getJvmInfo());

        return health;
    }

    @Override
    public void checkDatabaseConnection() {
        try {
            // 간단한 쿼리로 DB 연결 확인
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (result == null || result != 1) {
                throw new RuntimeException("Database query returned unexpected result");
            }
            log.debug("Database connection successful");
        } catch (Exception e) {
            log.error("Database connection failed", e);
            throw new RuntimeException("Database connection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void checkRedisConnection() {
        try {
            // Redis ping 명령으로 연결 확인
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();

            if (!"PONG".equals(pong)) {
                throw new RuntimeException("Redis ping returned unexpected result: " + pong);
            }

            // 간단한 set/get 테스트
            String testKey = "healthcheck:test";
            String testValue = "healthcheck-" + System.currentTimeMillis();

            redisTemplate.opsForValue().set(testKey, testValue, 10, TimeUnit.SECONDS);
            String retrievedValue = (String) redisTemplate.opsForValue().get(testKey);

            if (!testValue.equals(retrievedValue)) {
                throw new RuntimeException("Redis set/get test failed");
            }

            // 테스트 키 삭제
            redisTemplate.delete(testKey);

            log.debug("Redis connection successful");
        } catch (Exception e) {
            log.error("Redis connection failed", e);
            throw new RuntimeException("Redis connection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void checkCrawl4AIConnection() {
        try {
            // RestTemplate이 없으면 스킵
            if (restTemplate == null) {
                log.warn("RestTemplate not configured, skipping Crawl4AI health check");
                return;
            }

            // Crawl4AI 서버 상태 확인
            String crawl4aiUrl = "http://crawl4ai-server:11235/health"; // 환경에 따라 설정 가능하도록 개선 필요

            String response = restTemplate.getForObject(crawl4aiUrl, String.class);

            if (response == null || !response.contains("healthy")) {
                throw new RuntimeException("Crawl4AI health check failed");
            }

            log.debug("Crawl4AI connection successful");
        } catch (Exception e) {
            log.warn("Crawl4AI connection failed (this is optional): {}", e.getMessage());
            // Crawl4AI는 선택적 의존성이므로 예외를 던지지 않고 경고만 로그
        }
    }

    /**
     * 컴포넌트 헬스체크를 실행하고 결과를 반환하는 헬퍼 메서드
     */
    private Map<String, Object> checkComponentHealth(Runnable healthCheck) {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            healthCheck.run();
            result.put("status", "healthy");
            result.put("message", "Connection successful");
        } catch (Exception e) {
            result.put("status", "unhealthy");
            result.put("message", e.getMessage());
            result.put("error", e.getClass().getSimpleName());
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            result.put("responseTime", duration + "ms");
        }

        return result;
    }

    /**
     * JVM 정보 수집
     */
    private Map<String, Object> getJvmInfo() {
        Map<String, Object> jvmInfo = new HashMap<>();

        Runtime runtime = Runtime.getRuntime();

        jvmInfo.put("maxMemory", formatBytes(runtime.maxMemory()));
        jvmInfo.put("totalMemory", formatBytes(runtime.totalMemory()));
        jvmInfo.put("freeMemory", formatBytes(runtime.freeMemory()));
        jvmInfo.put("usedMemory", formatBytes(runtime.totalMemory() - runtime.freeMemory()));
        jvmInfo.put("availableProcessors", runtime.availableProcessors());

        // Java 버전 정보
        jvmInfo.put("javaVersion", System.getProperty("java.version"));
        jvmInfo.put("javaVendor", System.getProperty("java.vendor"));

        return jvmInfo;
    }

    /**
     * 바이트를 읽기 쉬운 형태로 포맷
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}