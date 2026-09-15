package com.stocker.catalog;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CatalogApplication {

	public static void main(String[] args) {

		Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

		String url = dotenv.get("STOCKER_DB_URL");
		String user = dotenv.get("STOCKER_DB_USER");

		System.out.println("DB URL = " + url);
		System.out.println("DB USER = " + user);

		System.setProperty("STOCKER_DB_URL", dotenv.get("STOCKER_DB_URL"));
		System.setProperty("STOCKER_DB_USER", dotenv.get("STOCKER_DB_USER"));
		System.setProperty("STOCKER_DB_PASSWORD", dotenv.get("STOCKER_DB_PASSWORD"));

		SpringApplication.run(CatalogApplication.class, args);
	}

}
