package app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for fetch-news.
 */
@SpringBootApplication
public class FetchNewsApplication {
    /**
     * Starts the application.
     *
     * @param args command-line arguments
     */
    static void main(String[] args) {
        SpringApplication.run(FetchNewsApplication.class, args);
    }
}
