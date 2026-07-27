package com.cleveft.transcriptionservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated pool for transcription jobs.
 *
 * <p>Kept small and explicitly bounded: each running job holds a full audio file
 * in memory and an open connection to the AI provider, so unbounded concurrency
 * would exhaust heap long before it exhausted CPU. Queued work waits rather than
 * being rejected — a student who has already uploaded should never be told to
 * upload again.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("transcriptionExecutor")
    public Executor transcriptionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("transcribe-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let an in-flight transcription finish rather than losing the work on
        // a rolling restart.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
