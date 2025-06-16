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
    CrawlStatusDto startDeepCrawling();

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
    // ===== 스케줄 관련 메서드 추가 =====

//    /**
//     * Deep Crawling 스케줄 시작
//     * @param intervalHours 실행 간격 (시간)
//     * @return 크롤링 상태
//     */
//    CrawlStatusDto startScheduledDeepCrawling(int intervalHours);
//
//    /**
//     * Deep Crawling 스케줄 중지
//     * @return 크롤링 상태
//     */
//    CrawlStatusDto stopScheduledDeepCrawling();
//
//    /**
//     * 스케줄 상태 조회
//     * @return 스케줄 상태 정보
//     */
//    Map<String, Object> getScheduleStatus();
}