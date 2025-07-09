package org.be.crawlerservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Data
@Component
@ConfigurationProperties(prefix = "alert")
public class AlertConfig {

    private Topics topics = new Topics();
    private Conditions conditions = new Conditions();

    @Data
    public static class Topics {
        private String input = "cdc-events";
        private String breakingAlerts = "breaking-alerts";
        private String trendingAlerts = "trending-alerts";
    }

    @Data
    public static class Conditions {
        private Breaking breaking = new Breaking();
        private Trending trending = new Trending();
    }

    @Data
    public static class Breaking {
        private int keywordThreshold = 50;
        private int timeWindowMinutes = 5;
        private int minSources = 3;
    }

    @Data
    public static class Trending {
        private double growthRateThreshold = 2.0;
        private int timeWindowMinutes = 30;
        private int minMentions = 10;
    }
}
