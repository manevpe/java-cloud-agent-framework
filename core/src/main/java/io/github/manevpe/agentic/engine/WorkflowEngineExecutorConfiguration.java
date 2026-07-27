package io.github.manevpe.agentic.engine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provides the background {@link ExecutorService} {@link
 * WorkflowEngineService} submits graph invocations to. A virtual-thread
 * executor is used since workflow threads spend most of their time
 * blocked (waiting on LLM calls, sandbox builds, or simply idle between
 * webhook events) rather than doing CPU work, and the number of
 * concurrently in-flight workflow threads is driven by ticket volume, not
 * a fixed pool size.
 */
@Configuration
public class WorkflowEngineExecutorConfiguration {

    @Bean
    public ExecutorService workflowExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
