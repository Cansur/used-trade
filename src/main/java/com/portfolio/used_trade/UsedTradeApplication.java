package com.portfolio.used_trade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan   // jwt.* 등 record 기반 @ConfigurationProperties 빈 등록
public class UsedTradeApplication {

	public static void main(String[] args) {
		SpringApplication.run(UsedTradeApplication.class, args);
	}

}
