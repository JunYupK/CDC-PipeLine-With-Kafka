package org.be.crawlerservice.service.health;

import java.util.Map;
public interface HealthCheckService {

    /**
     * 전체 시스템의 상세 헬스체크
     */
    Map<String, Object> getDetailedHealth();

    /**
     * 데이터베이스 연결 상태 확인
     */
    void checkDatabaseConnection();

    /**
     * Redis 연결 상태 확인
     */
    void checkRedisConnection();

    /**
     * 외부 API (Crawl4AI) 연결 상태 확인
     */
    void checkCrawl4AIConnection();
}