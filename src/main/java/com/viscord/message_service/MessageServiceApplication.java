package com.viscord.message_service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class MessageServiceApplication implements CommandLineRunner {
	public static void main(String[] args) {
		SpringApplication.run(MessageServiceApplication.class, args);
	}

	@Override
	public void run(String... args) {
	}
}
