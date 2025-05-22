// DatabaseConfig.java - 간단한 JPA 설정
package org.be.crawlerservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "org.be.crawlerservice.repository")
public class DatabaseConfig {
    // 기본 JPA 설정만 활성화
}