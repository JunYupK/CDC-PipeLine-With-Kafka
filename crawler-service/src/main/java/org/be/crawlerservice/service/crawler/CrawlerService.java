package org.be.crawlerservice.service.crawler;

import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.dto.response.CrawlStatusDto;
import org.be.crawlerservice.dto.response.StatsResponseDto;

import java.util.Map;

public interface CrawlerService {

    /**
     * 기본 크롤링 시작
     */
    CrawlStatusDto startCrawling(CrawlRequestDto request);

    /**
     * BFS Deep Crawling 시작
     */
    CrawlStatusDto startDeepCrawling(CrawlRequestDto request);

    /**
     * 크롤링 중지
     */
    CrawlStatusDto stopCrawling();

    /**
     * 현재 상태 조회
     */
    CrawlStatusDto getCurrentStatus();

    /**
     * 크롤링 통계 조회
     */
    StatsResponseDto getCrawlingStats();

    /**
     * 마지막 실행 시간들
     */
    Map<String, String> getLastExecutionTimes();

    /**
     * 성공률들
     */
    Map<String, Double> getSuccessRates();
}