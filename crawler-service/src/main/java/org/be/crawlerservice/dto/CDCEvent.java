package org.be.crawlerservice.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CDCEvent {
    private String operation; // INSERT, UPDATE, DELETE
    private Map<String, Object> before;
    private Map<String, Object> after;
    private LocalDateTime timestamp;
    private String table;
    private String source;
}
