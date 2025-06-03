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


            String crawlUrl = crawlerProperties.getCrawl4aiUrl() + "/crawl";
            log.debug("크롤링 엔드포인트: {}", crawlUrl);
            HttpHeaders headers = createHeaders();
            HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(crawlUrl, requestEntity, String.class);

            log.debug("응답 상태: {}, 본문: {}", response.getStatusCode(),
                    response.getBody() != null ? response.getBody().substring(0, Math.min(200, response.getBody().length())) : "null");

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode responseJson = objectMapper.readTree(response.getBody());
                Crawl4AIResult result = parseStatusResponse(response.getBody());
                return result;
            }
            // 2. 결과 폴링
        } catch (JsonProcessingException e) {
            log.error("JSON 직렬화 실패", e);
            throw new CrawlException("JSON serialization failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("크롤링 작업 실패: URLs={}", request.getUrls(), e);
            throw new CrawlException("Crawl operation failed: " + e.getMessage(), e);
        }
        return null;


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
     * 상태 응답 파싱 (수정된 버전 - results 배열 처리)
     */
    private Crawl4AIResult parseStatusResponse(String responseBody) throws JsonProcessingException {
        JsonNode responseJson = objectMapper.readTree(responseBody);

        // 전체 응답 구조 로깅 (디버깅용)
        log.debug("=== Crawl4AI 응답 구조 분석 ===");
        //log.debug("전체 응답: {}", responseJson.toPrettyString());
        //String status = responseJson.get("status").asText();
        //log.debug("상태: {}", status);

        Crawl4AIResult.Crawl4AIResultBuilder resultBuilder = Crawl4AIResult.builder();


        JsonNode resultNode = responseJson.get("results");
        log.info("=== 디버깅: 실제 데이터 확인 ===");

        // HTML 길이 확인
        if (resultNode.has("html")) {
            String html = resultNode.get("html").asText();
            log.info("HTML 길이: {}", html.length());
        }

        // cleaned_html 길이 확인
        if (resultNode.has("cleaned_html")) {
            String cleanedHtml = resultNode.get("cleaned_html").asText();
            log.info("Cleaned HTML 길이: {}", cleanedHtml.length());
        }

        // markdown 구조 확인
        if (resultNode.has("markdown")) {
            JsonNode markdownNode = resultNode.get("markdown");
            log.info("Markdown 타입: {}", markdownNode.getNodeType());
            if (markdownNode.isObject()) {
                log.info("raw_markdown 길이: {}",
                        getTextValue(markdownNode, "raw_markdown").length());
            }
        }

        // extracted_content 확인
        if (resultNode.has("extracted_content")) {
            JsonNode extracted = resultNode.get("extracted_content");
            log.info("Extracted content - null: {}, 빈문자열: {}",
                    extracted.isNull(),
                    !extracted.isNull() && extracted.asText().isEmpty());
        }
        JsonNode firstResult = resultNode.get(0);

        log.info("=== 필드별 상세 분석 ===");
        log.info("html 존재: {}, 값: {}", firstResult.has("html"),
                firstResult.has("html") ? firstResult.get("html").getNodeType() : "없음");
        log.info("cleaned_html 존재: {}, 값: {}", firstResult.has("cleaned_html"),
                firstResult.has("cleaned_html") ? firstResult.get("cleaned_html").getNodeType() : "없음");
        log.info("extracted_content 존재: {}, 값: {}", firstResult.has("extracted_content"),
                firstResult.has("extracted_content") ? firstResult.get("extracted_content").getNodeType() : "없음");
        System.out.println(firstResult.get("extracted_content"));
        Crawl4AIResult.CrawlResult crawlResult = parseCrawlResult(firstResult);
        resultBuilder.result(crawlResult).completedTime(LocalDateTime.now());

        System.out.println("=== EXTRACTED CONTENT 상세 분석 ===");
        JsonNode extractedNode = firstResult.get("extracted_content");
        System.out.println("extracted_content 타입: " + extractedNode.getNodeType());
        System.out.println("extracted_content isNull: " + extractedNode.isNull());
        System.out.println("extracted_content 원본: " + extractedNode.toString());
        System.out.println("extracted_content asText: '" + extractedNode.asText() + "'");

//        // 🔍 links 객체도 확인
//        JsonNode linksNode = firstResult.get("links");
//        System.out.println("=== LINKS 객체 확인 ===");
//        System.out.println(linksNode.toPrettyString());


        return resultBuilder.build();
    }

    /**
     * 크롤링 결과 파싱
     */
    private Crawl4AIResult.CrawlResult parseCrawlResult(JsonNode resultNode) {
        Crawl4AIResult.CrawlResult.CrawlResultBuilder builder = Crawl4AIResult.CrawlResult.builder();

        // 기본 필드들
        builder.html(getTextValue(resultNode, "html"));
        builder.cleanedHtml(getTextValue(resultNode, "cleaned_html"));

        // ✅ markdown 객체 처리
        if (resultNode.has("markdown") && !resultNode.get("markdown").isNull()) {
            JsonNode markdownNode = resultNode.get("markdown");
            if (markdownNode.isObject()) {
                // raw_markdown 사용
                String rawMarkdown = getTextValue(markdownNode, "raw_markdown");
                builder.markdown(rawMarkdown);

                // 추가 마크다운 정보도 저장 가능
                String fitMarkdown = getTextValue(markdownNode, "fit_markdown");
                // builder.fitMarkdown(fitMarkdown); // 필요시 추가
            } else {
                // 혹시 문자열인 경우 대비
                builder.markdown(markdownNode.asText());
            }
        }

        // ✅ extracted_content 처리 (null vs 빈문자열 구분)
        if (resultNode.has("extracted_content")) {
            JsonNode extractedNode = resultNode.get("extracted_content");
            if (!extractedNode.isNull()) {
                String extracted = extractedNode.asText();
                builder.extractedContent(extracted.isEmpty() ? null : extracted);
            }
        }



        return builder.build();
    }
    private String getTextValue(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            String value = node.get(fieldName).asText();
            return value.isEmpty() ? null : value;
        }
        return null;
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