package com.cleveft.transcriptionservice;

import com.cleveft.transcriptionservice.ai.GeminiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GeminiProperties.class)
public class TranscriptionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TranscriptionServiceApplication.class, args);
    }
}
