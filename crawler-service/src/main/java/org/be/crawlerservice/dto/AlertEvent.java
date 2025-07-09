package org.be.crawlerservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlertEvent {
    private String id;
    private String type; // breaking, trending
    private String title;
    private String content;
    private List<String> keywords;
    private Map<String, Object> metadata;
    private LocalDateTime timestamp;
    private double severity; // 0.0 ~ 1.0
    private String category;
    private List<String> sources;
}
