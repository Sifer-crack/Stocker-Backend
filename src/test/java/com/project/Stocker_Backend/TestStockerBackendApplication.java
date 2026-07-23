package com.project.Stocker_Backend;

import org.springframework.boot.SpringApplication;

public class TestStockerBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(StockerBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
