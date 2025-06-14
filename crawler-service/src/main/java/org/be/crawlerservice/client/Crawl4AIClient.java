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
import java.util.Map;
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
     * Deep Crawling BFS 실행
     */
    public CompletableFuture<List<Crawl4AIResult>> crawlBFSAsync(String startUrl, int maxDepth, int maxPages, Map<String, Object> schema ) {

        return CompletableFuture.supplyAsync(() -> {
            try {

                log.info("BFS Deep Crawling 시작: url={}, depth={}, pages={}", startUrl, maxDepth, maxPages);
                // BFS Deep Crawling 요청 생성
                Crawl4AIRequest request = Crawl4AIRequest.forBFSDeepCrawl(startUrl, maxDepth, maxPages, schema);
                String crawlUrl = crawlerProperties.getCrawl4aiUrl() + "/crawl";
                HttpHeaders headers = createHeaders();
                String requestJson = objectMapper.writeValueAsString(request);
                HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);

                //딥 크롤링 시작
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
                log.info("BFS Deep Crawling 완료: 총 {}개 페이지 처리", results.size());
                return crawlResults;

            } catch (Exception e) {
                log.error("BFS Deep Crawling 실패: {}", startUrl, e);
                throw new CrawlException("BFS Deep Crawling failed: " + e.getMessage(), e);
            }
        });
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

        builder.url(getTextValue(resultNode, "url"));

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