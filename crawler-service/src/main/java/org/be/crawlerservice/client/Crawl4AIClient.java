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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Crawl4AI Docker API 클라이언트 (개선된 버전)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Crawl4AIClient {

    private final RestTemplate restTemplate;
    private final CrawlerProperties crawlerProperties;
    private final ObjectMapper objectMapper;

    // 상수 정의
    private static final int DEFAULT_POLL_INTERVAL = 3; // 초
    private static final int DEFAULT_TIMEOUT = 180; // 초
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY = 2000; // 밀리초

    /**
     * 동기적으로 크롤링 실행
     */
    public Crawl4AIResult crawl(Crawl4AIRequest request) {
        return crawl(request, DEFAULT_POLL_INTERVAL, DEFAULT_TIMEOUT);
    }

    /**
     * 동기적으로 크롤링 실행 (커스텀 타임아웃)
     */
    public Crawl4AIResult crawl(Crawl4AIRequest request, int pollInterval, int timeoutSeconds) {
        try {
            log.info("크롤링 시작: URLs={}", request.getUrls());

            // JSON 직렬화 및 로깅
            String requestJson = objectMapper.writeValueAsString(request);
            log.debug("요청 JSON: {}", requestJson);

            // 1. 작업 제출
            String taskId = submitCrawlTask(request, requestJson);
            if (taskId == null) {
                return Crawl4AIResult.failed(null, "Failed to submit crawl task");
            }

            log.info("작업 제출 성공. Task ID: {}", taskId);

            // 2. 결과 폴링
            return pollForResult(taskId, pollInterval, timeoutSeconds);

        } catch (JsonProcessingException e) {
            log.error("JSON 직렬화 실패", e);
            throw new CrawlException("JSON serialization failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("크롤링 작업 실패: URLs={}", request.getUrls(), e);
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
     * Crawl4AI 서버 헬스 체크
     */
    public boolean isHealthy() {
        try {
            String healthUrl = crawlerProperties.getCrawl4aiUrl() + "/health";
            log.debug("헬스 체크 URL: {}", healthUrl);

            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);

            boolean healthy = response.getStatusCode() == HttpStatus.OK;
            log.debug("헬스 체크 결과: {} (상태코드: {})", healthy, response.getStatusCode());

            return healthy;
        } catch (Exception e) {
            log.warn("Crawl4AI 헬스 체크 실패: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 1단계: 크롤링 작업 제출 (개선된 로깅)
     */
    private String submitCrawlTask(Crawl4AIRequest request, String requestJson) {
        String crawlUrl = crawlerProperties.getCrawl4aiUrl() + "/crawl";
        log.debug("크롤링 엔드포인트: {}", crawlUrl);

        HttpHeaders headers = createHeaders();
        HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.debug("작업 제출 시도 {}/{}: URLs={}", attempt, MAX_RETRIES, request.getUrls());

                ResponseEntity<String> response = restTemplate.postForEntity(crawlUrl, requestEntity, String.class);

                log.debug("응답 상태: {}, 본문: {}", response.getStatusCode(),
                        response.getBody() != null ? response.getBody().substring(0, Math.min(200, response.getBody().length())) : "null");

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    JsonNode responseJson = objectMapper.readTree(response.getBody());

                    if (responseJson.has("task_id")) {
                        String taskId = responseJson.get("task_id").asText();
                        if (taskId != null && !taskId.isEmpty()) {
                            log.debug("Task ID 추출 성공: {}", taskId);
                            return taskId;
                        } else {
                            log.error("빈 task_id 수신: {}", responseJson);
                        }
                    } else {
                        log.error("task_id 필드 없음. 응답: {}", responseJson);
                    }
                } else {
                    log.error("작업 제출 실패 ({}): {}", response.getStatusCode(), response.getBody());
                }

            } catch (RestClientException e) {
                log.warn("네트워크 오류로 작업 제출 실패 (시도 {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (e.getMessage().contains("Connection refused")) {
                    log.error("Crawl4AI 서버에 연결할 수 없습니다. Docker 컨테이너가 실행 중인지 확인하세요.");
                }
            } catch (JsonProcessingException e) {
                log.error("JSON 파싱 실패 (시도 {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
            } catch (Exception e) {
                log.error("예상치 못한 오류 (시도 {}/{}): {}", attempt, MAX_RETRIES, e.getMessage(), e);
            }

            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY * attempt); // 지수 백오프
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return null;
    }

    /**
     * 2단계: 결과 폴링 (개선된 로깅)
     */
    private Crawl4AIResult pollForResult(String taskId, int pollInterval, int timeoutSeconds) {
        String statusUrl = crawlerProperties.getCrawl4aiUrl() + "/task/" + taskId;
        //log.debug("상태 확인 URL: {}", statusUrl);

        HttpHeaders headers = createHeaders();
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;
        int pollCount = 0;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                // 폴링 간격 대기
                Thread.sleep(pollInterval * 1000L);
                pollCount++;

                //log.trace("상태 확인 #{} for task: {}", pollCount, taskId);

                ResponseEntity<String> response = restTemplate.exchange(
                        statusUrl, HttpMethod.GET, requestEntity, String.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    //log.trace("상태 응답: {}", response.getBody().substring(0, Math.min(200, response.getBody().length())));
                    Crawl4AIResult result = parseStatusResponse(taskId, response.getBody());

                    if (result.isCompleted()) {
                        //log.info("작업 {} 완료 성공 ({}초 후)", taskId, (System.currentTimeMillis() - startTime) / 1000);
                        return result;
                    } else if (result.isFailed()) {
                        //log.error("작업 {} 실패: {}", taskId, result.getError());
                        return result;
                    } else {
                        //log.debug("작업 {} 상태: {} (폴링 #{})", taskId, result.getStatus(), pollCount);
                        // 계속 폴링
                    }
                } else {
                   // log.warn("상태 확인 실패 task {} ({}): {}",
                            //taskId, response.getStatusCode(), response.getBody());

                    // 상태 확인 실패 시 잠시 후 재시도
                    Thread.sleep(pollInterval * 2000L);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
               //log.warn("폴링 중단: {}", taskId);
                return Crawl4AIResult.failed(taskId, "Polling interrupted");
            } catch (RestClientException | JsonProcessingException e) {
                //log.warn("상태 확인 중 네트워크 오류 task {}: {}. 재시도...", taskId, e.getMessage());
                try {
                    Thread.sleep(pollInterval * 2000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 타임아웃
        log.error("작업 {} 타임아웃 ({}초 후)", taskId, timeoutSeconds);
        return Crawl4AIResult.failed(taskId, String.format("Task timed out after %d seconds", timeoutSeconds));
    }

    /**
     * 상태 응답 파싱 (수정된 버전 - results 배열 처리)
     */
    private Crawl4AIResult parseStatusResponse(String taskId, String responseBody) throws JsonProcessingException {
        JsonNode responseJson = objectMapper.readTree(responseBody);

        // 전체 응답 구조 로깅 (디버깅용)
        log.debug("=== Crawl4AI 응답 구조 분석 ===");
        log.debug("Task ID: {}", taskId);
        //log.debug("전체 응답: {}", responseJson.toPrettyString());

        String status = responseJson.get("status").asText();
        log.debug("상태: {}", status);

        Crawl4AIResult.Crawl4AIResultBuilder resultBuilder = Crawl4AIResult.builder()
                .taskId(taskId)
                .status(status);

        if ("completed".equals(status)) {
            // "result" 또는 "results" 노드 확인
            JsonNode resultNode = responseJson.get("result");
            JsonNode resultsNode = responseJson.get("results");

            log.debug("result 노드 존재: {}", resultNode != null);
            log.debug("results 노드 존재: {}", resultsNode != null);

            if (resultNode != null) {
                // 단일 result 노드 처리 (기존 방식)
                log.debug("단일 result 노드 처리");
                log.debug("result 내용: {}", resultNode.toPrettyString());

                Crawl4AIResult.CrawlResult crawlResult = parseCrawlResult(resultNode);
                resultBuilder.result(crawlResult).completedTime(LocalDateTime.now());

            } else if (resultsNode != null && resultsNode.isArray() && resultsNode.size() > 0) {
                // results 배열 처리 (새로운 방식)
                log.debug("results 배열 처리 - 배열 크기: {}", resultsNode.size());
                JsonNode firstResult = resultsNode.get(0);
                //log.debug("첫 번째 결과 내용: {}", firstResult.toPrettyString());

                // 첫 번째 결과의 모든 필드 로깅
//                firstResult.fieldNames().forEachRemaining(fieldName -> {
//                    JsonNode fieldValue = firstResult.get(fieldName);
//                    if (fieldValue.isTextual() && fieldValue.asText().length() > 100) {
//                        log.debug("  - {}: {}... (길이: {})", fieldName,
//                                fieldValue.asText().substring(0, 100), fieldValue.asText().length());
//                    } else {
//                        log.debug("  - {}: {} (타입: {})", fieldName, fieldValue.toString(), fieldValue.getNodeType());
//                    }
//                });

                Crawl4AIResult.CrawlResult crawlResult = parseCrawlResult(firstResult);
                resultBuilder.result(crawlResult).completedTime(LocalDateTime.now());

            } else {
                log.warn("완료된 작업이지만 result 또는 results 노드가 없거나 비어있음: {}", taskId);
            }
        } else if ("failed".equals(status)) {
            String error = responseJson.has("error") ? responseJson.get("error").asText() : "Unknown error";
            log.debug("실패 오류: {}", error);
            resultBuilder.error(error).completedTime(LocalDateTime.now());
        }

        log.debug("=== 응답 구조 분석 완료 ===");
        return resultBuilder.build();
    }

    /**
     * 크롤링 결과 파싱
     */
    private Crawl4AIResult.CrawlResult parseCrawlResult(JsonNode resultNode) {
        Crawl4AIResult.CrawlResult.CrawlResultBuilder builder = Crawl4AIResult.CrawlResult.builder();
        System.out.println("result check");
        List<String> keys = new ArrayList<>();
        Iterator<String> fieldNames = resultNode.fieldNames();
        while (fieldNames.hasNext()) {
            keys.add(fieldNames.next());
        }
        System.out.println(keys);
        // 기본 필드들
        if (resultNode.has("html")) {
            builder.html(resultNode.get("html").asText());
        }
        if (resultNode.has("cleaned_html")) {
            System.out.println("클린클린");
            builder.cleanedHtml(resultNode.get("cleaned_html").asText());
        }
        if (resultNode.has("markdown")) {
            builder.markdown(resultNode.get("markdown").asText());
        }
        if (resultNode.has("extracted_content")) {
            System.out.println("추출 추출");
            System.out.println("추출 추출");
            System.out.println(resultNode.get("extracted_content"));
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
}