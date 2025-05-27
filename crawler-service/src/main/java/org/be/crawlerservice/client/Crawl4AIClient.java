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
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Crawl4AI Docker API 클라이언트
 * Python crawl4ai_helper.py의 _call_crawl4ai_api 함수를 Java로 구현
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Crawl4AIClient {

    private final RestTemplate restTemplate;
    private final CrawlerProperties crawlerProperties;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService pollingExecutor = Executors.newScheduledThreadPool(5);

    // 상수 정의 (Python 코드와 동일)
    private static final int DEFAULT_POLL_INTERVAL = 3; // 초
    private static final int DEFAULT_TIMEOUT = 180; // 초
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY = 2000; // 밀리초

    /**
     * 동기적으로 크롤링 실행
     * Python의 _call_crawl4ai_api와 동일한 로직
     */
    public Crawl4AIResult crawl(Crawl4AIRequest request) {
        return crawl(request, DEFAULT_POLL_INTERVAL, DEFAULT_TIMEOUT);
    }

    /**
     * 동기적으로 크롤링 실행 (커스텀 타임아웃)
     */
    public Crawl4AIResult crawl(Crawl4AIRequest request, int pollInterval, int timeoutSeconds) {
        try {
            log.info("Starting crawl for URL: {}", request.getUrls());

            // 1. 작업 제출
            String taskId = submitCrawlTask(request);
            if (taskId == null) {
                return Crawl4AIResult.failed(null, "Failed to submit crawl task");
            }

            log.info("Task submitted successfully. Task ID: {}", taskId);

            // 2. 결과 폴링
            return pollForResult(taskId, pollInterval, timeoutSeconds);

        } catch (Exception e) {
            log.error("Crawl operation failed for URL: {}", request.getUrls(), e);
            throw new CrawlException("Crawl operation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 비동기적으로 크롤링 실행
     */
    public CompletableFuture<Crawl4AIResult> crawlAsync(Crawl4AIRequest request) {
        return CompletableFuture.supplyAsync(() -> crawl(request));
    }

    /**
     * 비동기적으로 크롤링 실행 (커스텀 타임아웃)
     */
    public CompletableFuture<Crawl4AIResult> crawlAsync(Crawl4AIRequest request, int pollInterval, int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> crawl(request, pollInterval, timeoutSeconds));
    }

    /**
     * Crawl4AI 서버 헬스 체크
     */
    public boolean isHealthy() {
        try {
            String healthUrl = crawlerProperties.getCrawl4aiUrl() + "/health";
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);

            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.warn("Crawl4AI health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 1단계: 크롤링 작업 제출
     * Python 코드의 첫 번째 POST 요청 부분
     */
    private String submitCrawlTask(Crawl4AIRequest request) {
        String crawlUrl = crawlerProperties.getCrawl4aiUrl() + "/crawl";
        HttpHeaders headers = createHeaders();

        HttpEntity<Crawl4AIRequest> requestEntity = new HttpEntity<>(request, headers);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.debug("Submitting crawl task (attempt {}/{}): {}", attempt, MAX_RETRIES, request.getUrls());

                ResponseEntity<String> response = restTemplate.postForEntity(crawlUrl, requestEntity, String.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    JsonNode responseJson = objectMapper.readTree(response.getBody());
                    String taskId = responseJson.get("task_id").asText();

                    if (taskId != null && !taskId.isEmpty()) {
                        return taskId;
                    } else {
                        log.error("No task_id received for URL: {}", request.getUrls());
                    }
                } else {
                    log.error("Error submitting task ({}): {}", response.getStatusCode(), response.getBody());
                }

            } catch (RestClientException | JsonProcessingException e) {
                log.warn("Error submitting crawl task (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY * attempt); // 지수 백오프
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 2단계: 결과 폴링
     * Python 코드의 폴링 루프 부분
     */
    private Crawl4AIResult pollForResult(String taskId, int pollInterval, int timeoutSeconds) {
        String statusUrl = crawlerProperties.getCrawl4aiUrl() + "/task/" + taskId;
        HttpHeaders headers = createHeaders();
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                // 폴링 간격 대기
                Thread.sleep(pollInterval * 1000L);

                log.trace("Checking status for task: {}", taskId);

                ResponseEntity<String> response = restTemplate.exchange(
                        statusUrl, HttpMethod.GET, requestEntity, String.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    Crawl4AIResult result = parseStatusResponse(taskId, response.getBody());

                    if (result.isCompleted()) {
                        log.info("Task {} completed successfully", taskId);
                        return result;
                    } else if (result.isFailed()) {
                        log.error("Task {} failed: {}", taskId, result.getError());
                        return result;
                    } else {
                        log.debug("Task {} status: {}", taskId, result.getStatus());
                        // 계속 폴링
                    }
                } else {
                    log.warn("Error checking status for task {} ({}): {}",
                            taskId, response.getStatusCode(), response.getBody());

                    // 상태 확인 실패 시 잠시 후 재시도
                    Thread.sleep(pollInterval * 2000L);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Polling interrupted for task: {}", taskId);
                return Crawl4AIResult.failed(taskId, "Polling interrupted");
            } catch (RestClientException | JsonProcessingException e) {
                log.warn("Network error checking status for task {}: {}. Retrying...", taskId, e.getMessage());
                try {
                    Thread.sleep(pollInterval * 2000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 타임아웃
        log.error("Task {} timed out after {} seconds", taskId, timeoutSeconds);
        return Crawl4AIResult.failed(taskId, String.format("Task timed out after %d seconds", timeoutSeconds));
    }

    /**
     * 상태 응답 파싱
     */
    private Crawl4AIResult parseStatusResponse(String taskId, String responseBody) throws JsonProcessingException {
        JsonNode responseJson = objectMapper.readTree(responseBody);
        String status = responseJson.get("status").asText();

        Crawl4AIResult.Crawl4AIResultBuilder resultBuilder = Crawl4AIResult.builder()
                .taskId(taskId)
                .status(status);

        if ("completed".equals(status)) {
            JsonNode resultNode = responseJson.get("result");
            if (resultNode != null) {
                Crawl4AIResult.CrawlResult crawlResult = parseCrawlResult(resultNode);
                resultBuilder.result(crawlResult).completedTime(LocalDateTime.now());
            }
        } else if ("failed".equals(status)) {
            String error = responseJson.has("error") ? responseJson.get("error").asText() : "Unknown error";
            resultBuilder.error(error).completedTime(LocalDateTime.now());
        }

        return resultBuilder.build();
    }

    /**
     * 크롤링 결과 파싱
     */
    private Crawl4AIResult.CrawlResult parseCrawlResult(JsonNode resultNode) {
        Crawl4AIResult.CrawlResult.CrawlResultBuilder builder = Crawl4AIResult.CrawlResult.builder();

        // 기본 필드들
        if (resultNode.has("html")) {
            builder.html(resultNode.get("html").asText());
        }
        if (resultNode.has("cleaned_html")) {
            builder.cleanedHtml(resultNode.get("cleaned_html").asText());
        }
        if (resultNode.has("markdown")) {
            builder.markdown(resultNode.get("markdown").asText());
        }
        if (resultNode.has("extracted_content")) {
            builder.extractedContent(resultNode.get("extracted_content").asText());
        }
        if (resultNode.has("success")) {
            builder.success(resultNode.get("success").asBoolean());
        }
        if (resultNode.has("status_code")) {
            builder.statusCode(resultNode.get("status_code").asInt());
        }
        if (resultNode.has("screenshot")) {
            builder.screenshot(resultNode.get("screenshot").asText());
        }
        if (resultNode.has("pdf")) {
            builder.pdf(resultNode.get("pdf").asText());
        }

        // TODO: media, links, headers, metadata 파싱 구현
        // 필요시 추가 구현

        return builder.build();
    }

    /**
     * HTTP 헤더 생성
     */
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // API 토큰이 설정되어 있으면 Authorization 헤더 추가
        if (crawlerProperties.getApiToken() != null && !crawlerProperties.getApiToken().isEmpty()) {
            headers.setBearerAuth(crawlerProperties.getApiToken());
        }

        return headers;
    }

    /**
     * 클라이언트 종료 시 리소스 정리
     */
    public void shutdown() {
        pollingExecutor.shutdown();
        try {
            if (!pollingExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                pollingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pollingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}