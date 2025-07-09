package org.be.crawlerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableKafkaStreams
@EnableAsync
@EnableScheduling
public class CrawlerServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CrawlerServiceApplication.class, args);
	}

}
