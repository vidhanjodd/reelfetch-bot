package com.reelfetch_bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ReelfetchBotApplication {

	public static void main(String[] args) {
		SpringApplication.run(ReelfetchBotApplication.class, args);
	}

}
