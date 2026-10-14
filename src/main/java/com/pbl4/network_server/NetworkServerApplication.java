package com.pbl4.network_server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NetworkServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(NetworkServerApplication.class, args);
	}

}
