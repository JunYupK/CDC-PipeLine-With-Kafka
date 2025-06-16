package org.be.crawlerservice.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.config.CrawlerProperties;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIRequest;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIResult;
import org.be.crawlerservice.exception.CrawlException;
import org.be.crawlerservice.service.docker.DockerContainerManager;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Crawl4AI Docker API 클라이언트 (자동 복구 기능 포함)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Crawl4AIClient {

    private final RestTemplate restTemplate;
    private final CrawlerProperties crawlerProperties;
    private final ObjectMapper objectMapper;
    private final DockerContainerManager dockerManager;

    // 상수 정의
    private static final int DEFAULT_POLL_INTERVAL = 3;
    private static final int DEFAULT_TIMEOUT = 300;
    private static final int MAX_RETRIES = 3;

    /**
     * 자동 복구 기능이 있는 Deep Crawling BFS 실행
     */
    public CompletableFuture<List<Crawl4AIResult>> crawlBFSAsync(String startUrl, int maxDepth, int maxPages, Map<String, Object> schema) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    log.info("🚀 BFS Deep Crawling 시도 {}/{}: {}", attempt, MAX_RETRIES, startUrl);

                    // 1. 사전 헬스체크 및 복구
                    if (!ensureContainerHealthy(attempt > 1)) {
                        if (attempt == MAX_RETRIES) {
                            throw new CrawlException("Crawl4AI 컨테이너 복구 실패 (최대 재시도 초과)");
                        }
                        continue;
                    }

                    // 2. 실제 크롤링 실행
                    List<Crawl4AIResult> results = executeBFSCrawling(startUrl, maxDepth, maxPages, schema);

                    log.info("✅ BFS Deep Crawling 성공: {}개 페이지 처리", results.size());
                    return results;

                } catch (CrawlException e) {
                    log.warn("⚠️ BFS Deep Crawling 시도 {} 실패: {}", attempt, e.getMessage());

                    if (attempt == MAX_RETRIES) {
                        log.error("❌ BFS Deep Crawling 최종 실패: {}", startUrl);
                        throw e;
                    }

                    // 실패 후 복구 시도
                    try {
                        log.info("🔧 실패 후 복구 시작 (시도 {})", attempt);
                        dockerManager.recoverCrawl4AI();
                        Thread.sleep(10000); // 10초 추가 대기
                    } catch (Exception recoveryEx) {
                        log.error("복구 중 오류: {}", recoveryEx.getMessage());
                    }
                }
            }

            throw new CrawlException("BFS Deep Crawling 최대 재시도 횟수 초과");
        });
    }

    /**
     * 컨테이너 상태 확인 및 필요시 복구
     */
    private boolean ensureContainerHealthy(boolean forceRecover) {
        try {
            // 1. Docker 사용 가능 여부 확인
            if (!dockerManager.isDockerAvailable()) {
                log.warn("Docker 명령어 사용 불가 - 복구 건너뜀");
                return true; // Docker가 없어도 진행 (개발환경 등)
            }

            // 2. 예방적 메모리 체크
            double memoryUsage = dockerManager.getContainerMemoryUsage("crawl4ai-server");
            if (memoryUsage > 80.0) {
                log.warn("🚨 높은 메모리 사용률 감지: {}% - 예방적 재시작", memoryUsage);
                forceRecover = true;
            }

            // 3. 헬스체크
            if (!forceRecover && dockerManager.isCrawl4AIHealthy()) {
                // 추가로 API 헬스체크
                if (isApiHealthy()) {
                    log.debug("✅ Crawl4AI 상태 양호");
                    return true;
                }
            }

            // 4. 복구 실행
            log.info("🔧 Crawl4AI 컨테이너 복구 시작");
            boolean recovered = dockerManager.recoverCrawl4AI();

            if (!recovered) {
                log.error("❌ 컨테이너 복구 실패");
                return false;
            }

            // 5. API 준비 대기
            return waitForApiReady(30);

        } catch (Exception e) {
            log.error("컨테이너 상태 확인 중 오류", e);
            return false;
        }
    }

    /**
     * API 헬스체크
     */
    private boolean isApiHealthy() {
        try {
            String healthUrl = crawlerProperties.getCrawl4aiUrl() + "/health";
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.debug("API 헬스체크 실패: {}", e.getMessage());
            return false;
        }
    }

    /**
     * API 준비 상태까지 대기
     */
    private boolean waitForApiReady(int timeoutSeconds) {
        log.info("⏳ Crawl4AI API 준비 대기 ({}초)", timeoutSeconds);

        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (isApiHealthy()) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                log.info("✅ Crawl4AI API 준비 완료 ({}초)", elapsed);
                return true;
            }

            try {
                Thread.sleep(3000); // 3초마다 재확인
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.error("❌ API 준비 타임아웃 ({}초)", timeoutSeconds);
        return false;
    }

    /**
     * 실제 BFS 크롤링 실행 (기존 로직)
     */
    private List<Crawl4AIResult> executeBFSCrawling(String startUrl, int maxDepth, int maxPages, Map<String, Object> schema) {
        try {
            log.debug("BFS Deep Crawling 실행: url={}, depth={}, pages={}", startUrl, maxDepth, maxPages);

            //Crawl4AIRequest request = Crawl4AIRequest.forBFSDeepCrawl(startUrl, maxDepth, maxPages, schema);
            Crawl4AIRequest request = Crawl4AIRequest.forBestFirstNaverNews(startUrl, maxDepth, maxPages, schema);
            String crawlUrl = crawlerProperties.getCrawl4aiUrl() + "/crawl";
            HttpHeaders headers = createHeaders();
            String requestJson = objectMapper.writeValueAsString(request);
            HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(crawlUrl, requestEntity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new CrawlException("API 호출 실패: " + response.getStatusCode());
            }

            JsonNode responseJson = objectMapper.readTree(response.getBody());
            JsonNode results = responseJson.get("results");
            List<Crawl4AIResult> crawlResults = new ArrayList<>();

            for (JsonNode resultNode : results) {
                Crawl4AIResult.CrawlResult crawlResult = parseCrawlResult(resultNode);
                Crawl4AIResult result = Crawl4AIResult.builder()
                        .result(crawlResult)
                        .completedTime(LocalDateTime.now())
                        .build();
                crawlResults.add(result);
            }

            return crawlResults;

        } catch (Exception e) {
            throw new CrawlException("BFS Deep Crawling 실행 실패: " + e.getMessage(), e);
        }
    }

    // === 기존 메서드들 (호환성 유지) ===

    /**
     * 기본 동기 크롤링 (기존 코드와 호환성 유지)
     */
    public Crawl4AIResult crawl(Crawl4AIRequest request, boolean flag) {
        if(flag) return crawl(request, DEFAULT_POLL_INTERVAL, DEFAULT_TIMEOUT);
        else return crawl(request);
    }

    public Crawl4AIResult crawl(Crawl4AIRequest request, int pollInterval, int timeoutSeconds) {
        try {
            log.info("크롤링 시작: URLs={}", request.getUrls());
            String requestJson = objectMapper.writeValueAsString(request);
            String crawlUrl = crawlerProperties.getCrawl4aiUrl() + "/crawl";
            HttpHeaders headers = createHeaders();
            HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(crawlUrl, requestEntity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseStatusResponse(response.getBody());
            }
        } catch (Exception e) {
            log.error("크롤링 작업 실패: URLs={}", request.getUrls(), e);
            throw new CrawlException("Crawl operation failed: " + e.getMessage(), e);
        }
        return null;
    }

    public Crawl4AIResult crawl(Crawl4AIRequest request) {
        return crawl(request, false);
    }

    /**
     * 헬스 체크 (외부에서 사용 가능)
     */
    public boolean isHealthy() {
        return isApiHealthy();
    }

    // === Private 헬퍼 메서드들 ===

    private String getTextValue(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            String value = node.get(fieldName).asText();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (crawlerProperties.getApiToken() != null && !crawlerProperties.getApiToken().isEmpty()) {
            headers.setBearerAuth(crawlerProperties.getApiToken());
        }
        return headers;
    }

    private Crawl4AIResult parseStatusResponse(String responseBody) throws JsonProcessingException {
        JsonNode responseJson = objectMapper.readTree(responseBody);
        Crawl4AIResult.Crawl4AIResultBuilder resultBuilder = Crawl4AIResult.builder();
        JsonNode resultNode = responseJson.get("results");
        JsonNode firstResult = resultNode.get(0);
        Crawl4AIResult.CrawlResult crawlResult = parseCrawlResult(firstResult);
        resultBuilder.result(crawlResult).completedTime(LocalDateTime.now());
        return resultBuilder.build();
    }

    /**
     * 크롤링 결과 파싱
     */
    private Crawl4AIResult.CrawlResult parseCrawlResult(JsonNode resultNode) {
        Crawl4AIResult.CrawlResult.CrawlResultBuilder builder = Crawl4AIResult.CrawlResult.builder();

        builder.url(getTextValue(resultNode, "url"));
        builder.html(getTextValue(resultNode, "html"));
        builder.cleanedHtml(getTextValue(resultNode, "cleaned_html"));

        // markdown 처리
        if (resultNode.has("markdown") && !resultNode.get("markdown").isNull()) {
            JsonNode markdownNode = resultNode.get("markdown");
            if (markdownNode.isObject()) {
                String rawMarkdown = getTextValue(markdownNode, "raw_markdown");
                builder.markdown(rawMarkdown);
            } else {
                builder.markdown(markdownNode.asText());
            }
        }

        // extracted_content 처리
        if (resultNode.has("extracted_content")) {
            JsonNode extractedNode = resultNode.get("extracted_content");
            if (!extractedNode.isNull()) {
                String extracted = extractedNode.asText();
                builder.extractedContent(extracted.isEmpty() ? null : extracted);
            }
        }

        // 성공 여부
        if (resultNode.has("success")) {
            builder.success(resultNode.get("success").asBoolean());
        }

        return builder.build();
    }
}