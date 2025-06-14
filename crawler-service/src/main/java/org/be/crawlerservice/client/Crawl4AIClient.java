package org.be.crawlerservice.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.config.CrawlerProperties;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIRequest;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIResult;
import org.be.crawlerservice.dto.crawl4ai.StreamingCrawlResult;
import org.be.crawlerservice.exception.CrawlException;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Crawl4AI Docker API 클라이언트 (Deep Crawling 지원)
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
    private static final int DEFAULT_TIMEOUT = 300; // Deep Crawling은 더 긴 타임아웃
    private static final int MAX_RETRIES = 3;

    /**
     * Deep Crawling BFS 실행 (스트리밍 모드)
     */
    public CompletableFuture<List<StreamingCrawlResult>> crawlBFSAsync(
            String startUrl,
            int maxDepth,
            int maxPages,
            Consumer<StreamingCrawlResult> onProgress) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("BFS Deep Crawling 시작: url={}, depth={}, pages={}", startUrl, maxDepth, maxPages);

                // BFS Deep Crawling 요청 생성
                Crawl4AIRequest request = Crawl4AIRequest.forBFSDeepCrawl(startUrl, maxDepth, maxPages);

                // 스트리밍 결과 수집
                List<StreamingCrawlResult> results = new ArrayList<>();

                // Deep Crawling 실행 및 스트리밍 처리
                executeDeepCrawlStreaming(request, results, onProgress);

                log.info("BFS Deep Crawling 완료: 총 {}개 페이지 처리", results.size());
                return results;

            } catch (Exception e) {
                log.error("BFS Deep Crawling 실패: {}", startUrl, e);
                throw new CrawlException("BFS Deep Crawling failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 네이버 뉴스 전용 BFS Deep Crawling
     */
    public CompletableFuture<List<StreamingCrawlResult>> crawlNaverNewsBFS(
            String categoryUrl,
            Consumer<StreamingCrawlResult> onProgress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("네이버 뉴스 BFS Deep Crawling 시작: {}", categoryUrl);

                Crawl4AIRequest request = Crawl4AIRequest.forNaverNewsBFS(categoryUrl);
                log.info("reauest :  정보 " + request);
                List<StreamingCrawlResult> results = new ArrayList<>();

                executeDeepCrawlStreaming(request, results, onProgress);

                log.info("네이버 뉴스 BFS Deep Crawling 완료: 총 {}개 페이지 처리", results.size());
                return results;

            } catch (Exception e) {
                log.error("네이버 뉴스 BFS Deep Crawling 실패: {}", categoryUrl, e);
                throw new CrawlException("Naver News BFS Deep Crawling failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Deep Crawling 스트리밍 실행
     */
    private void executeDeepCrawlStreaming(
            Crawl4AIRequest request,
            List<StreamingCrawlResult> results,
            Consumer<StreamingCrawlResult> onProgress) throws Exception {

        // 1. Deep Crawling 작업 시작
        String taskId = startDeepCrawlTask(request);
        log.info("Deep Crawling 작업 시작됨: taskId={}", taskId);

        // 2. 스트리밍 결과 폴링
        boolean isCompleted = false;
        int processedCount = 0;
        long startTime = System.currentTimeMillis();

        while (!isCompleted && (System.currentTimeMillis() - startTime) < (DEFAULT_TIMEOUT * 1000)) {
            try {
                Thread.sleep(DEFAULT_POLL_INTERVAL * 1000);

                // 현재 상태 확인
                StreamingCrawlResult currentResult = checkDeepCrawlStatus(taskId);

                if (currentResult != null) {
                    // 진행 상황 콜백 호출
                    if (onProgress != null) {
                        onProgress.accept(currentResult);
                    }

                    // 새로운 페이지가 처리되었으면 결과에 추가
                    if (currentResult.isPageSuccessful() && currentResult.hasPageContent()) {
                        results.add(currentResult);
                        processedCount++;
                        log.debug("새 페이지 처리 완료 ({}/{}): {}",
                                processedCount,
                                currentResult.getTotalEstimatedPages(),
                                currentResult.getCurrentUrl());
                    }

                    // 완료 상태 확인
                    isCompleted = currentResult.isStreamCompleted() || currentResult.isStreamFailed();

                    if (currentResult.isStreamFailed()) {
                        log.warn("Deep Crawling 작업 실패: taskId={}", taskId);
                        break;
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Deep Crawling 작업 중단: taskId={}", taskId);
                break;
            } catch (Exception e) {
                log.error("Deep Crawling 상태 확인 중 오류: taskId={}", taskId, e);
            }
        }

        if (!isCompleted) {
            log.warn("Deep Crawling 타임아웃: taskId={}, processed={}", taskId, processedCount);
        }
    }

    /**
     * Deep Crawling 작업 시작
     */
    private String startDeepCrawlTask(Crawl4AIRequest request) throws Exception {
        String crawlUrl = crawlerProperties.getCrawl4aiUrl() + "/crawl";

        HttpHeaders headers = createHeaders();
        String requestJson = objectMapper.writeValueAsString(request);
        HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);

        log.debug("Deep Crawling 요청 전송: {}", crawlUrl);

        ResponseEntity<String> response = restTemplate.postForEntity(crawlUrl, requestEntity, String.class);
        log.info("response 정보 : " + response);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            JsonNode responseJson = objectMapper.readTree(response.getBody());

            // 작업 ID 추출 (스트리밍 모드에서는 task_id 반환)
            if (responseJson.has("task_id")) {
                return responseJson.get("task_id").asText();
            } else if (responseJson.has("results")) {
                // 즉시 완료된 경우 임시 ID 생성
                return "immediate_" + System.currentTimeMillis();
            }
        }

        throw new CrawlException("Deep Crawling 작업 시작 실패: " + response.getStatusCode());
    }

    /**
     * Deep Crawling 상태 확인
     */
    private StreamingCrawlResult checkDeepCrawlStatus(String taskId) throws Exception {
        String statusUrl = crawlerProperties.getCrawl4aiUrl() + "/status/" + taskId;

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(statusUrl, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseStreamingResult(response.getBody(), taskId);
            }
        } catch (Exception e) {
            log.debug("상태 확인 실패 (정상적일 수 있음): taskId={}", taskId);
        }

        return null;
    }

    /**
     * 스트리밍 결과 파싱
     */
    private StreamingCrawlResult parseStreamingResult(String responseBody, String taskId) throws JsonProcessingException {
        JsonNode responseJson = objectMapper.readTree(responseBody);

        StreamingCrawlResult.StreamingCrawlResultBuilder builder = StreamingCrawlResult.builder();
        builder.taskId(taskId);
        builder.timestamp(LocalDateTime.now());

        // 기본 상태 정보
        if (responseJson.has("status")) {
            builder.streamStatus(responseJson.get("status").asText());
        }

        if (responseJson.has("current_url")) {
            builder.currentUrl(responseJson.get("current_url").asText());
        }

        if (responseJson.has("current_depth")) {
            builder.currentDepth(responseJson.get("current_depth").asInt());
        }

        if (responseJson.has("processed_pages")) {
            builder.processedPages(responseJson.get("processed_pages").asInt());
        }

        if (responseJson.has("total_estimated_pages")) {
            builder.totalEstimatedPages(responseJson.get("total_estimated_pages").asInt());
        }

        if (responseJson.has("progress_percentage")) {
            builder.progressPercentage(responseJson.get("progress_percentage").asDouble());
        }

        // 발견된 URL들
        if (responseJson.has("discovered_urls")) {
            JsonNode urlsNode = responseJson.get("discovered_urls");
            List<String> discoveredUrls = new ArrayList<>();
            if (urlsNode.isArray()) {
                urlsNode.forEach(node -> discoveredUrls.add(node.asText()));
            }
            builder.discoveredUrls(discoveredUrls);
        }

        // 현재 페이지 크롤링 결과
        if (responseJson.has("page_result")) {
            JsonNode pageResultNode = responseJson.get("page_result");
            Crawl4AIResult.CrawlResult pageResult = parseCrawlResult(pageResultNode);
            builder.pageResult(pageResult);
        }

        // 메타데이터
        if (responseJson.has("metadata")) {
            JsonNode metadataNode = responseJson.get("metadata");
            StreamingCrawlResult.DeepCrawlMetadata metadata = parseDeepCrawlMetadata(metadataNode);
            builder.metadata(metadata);
        }

        return builder.build();
    }

    /**
     * Deep Crawl 메타데이터 파싱
     */
    private StreamingCrawlResult.DeepCrawlMetadata parseDeepCrawlMetadata(JsonNode metadataNode) {
        StreamingCrawlResult.DeepCrawlMetadata.DeepCrawlMetadataBuilder builder =
                StreamingCrawlResult.DeepCrawlMetadata.builder();

        if (metadataNode.has("score")) {
            builder.score(metadataNode.get("score").asDouble());
        }

        if (metadataNode.has("url_category")) {
            builder.urlCategory(metadataNode.get("url_category").asText());
        }

        if (metadataNode.has("parent_url")) {
            builder.parentUrl(metadataNode.get("parent_url").asText());
        }

        if (metadataNode.has("crawl_time")) {
            builder.crawlTime(metadataNode.get("crawl_time").asLong());
        }

        if (metadataNode.has("extracted_link_count")) {
            builder.extractedLinkCount(metadataNode.get("extracted_link_count").asInt());
        }

        return builder.build();
    }

    /**
     * 크롤링 결과 파싱 (기존 메서드 재사용)
     */
    private Crawl4AIResult.CrawlResult parseCrawlResult(JsonNode resultNode) {
        Crawl4AIResult.CrawlResult.CrawlResultBuilder builder = Crawl4AIResult.CrawlResult.builder();

        // 기본 필드들
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

    // === 기존 메서드들 (호환성 유지) ===

    /**
     * 기본 동기 크롤링 (기존 코드와 호환성 유지)
     */
    public Crawl4AIResult crawl(Crawl4AIRequest request, boolean flag) {
        if(flag) return crawl(request, DEFAULT_POLL_INTERVAL, DEFAULT_TIMEOUT);
        else return crawl(request);
    }

    public Crawl4AIResult crawl(Crawl4AIRequest request, int pollInterval, int timeoutSeconds) {
        // 기존 구현 유지
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
     * 헬스 체크
     */
    public boolean isHealthy() {
        try {
            String healthUrl = crawlerProperties.getCrawl4aiUrl() + "/health";
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.warn("Crawl4AI 헬스 체크 실패: {}", e.getMessage());
            return false;
        }
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
        // 기존 구현 유지 (간소화)
        JsonNode responseJson = objectMapper.readTree(responseBody);
        Crawl4AIResult.Crawl4AIResultBuilder resultBuilder = Crawl4AIResult.builder();
        JsonNode resultNode = responseJson.get("results");
        JsonNode firstResult = resultNode.get(0);
        Crawl4AIResult.CrawlResult crawlResult = parseCrawlResult(firstResult);
        resultBuilder.result(crawlResult).completedTime(LocalDateTime.now());
        return resultBuilder.build();
    }
}