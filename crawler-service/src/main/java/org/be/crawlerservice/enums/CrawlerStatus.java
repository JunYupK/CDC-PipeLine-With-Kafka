package org.be.crawlerservice.enums;

public enum CrawlerStatus {
    IDLE("대기 중"),
    RUNNING("실행 중"),
    STOPPING("중지 중"),
    COMPLETED("완료"),
    FAILED("실패"),
    PAUSED("일시정지");

    private final String description;

    CrawlerStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}