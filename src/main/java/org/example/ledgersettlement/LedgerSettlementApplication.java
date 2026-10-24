package org.example.ledgersettlement;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

@SpringBootApplication
public class LedgerSettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerSettlementApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public ExecutorService applicationVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    public AsyncTaskExecutor applicationTaskExecutor(ExecutorService applicationVirtualThreadExecutor) {
        return new TaskExecutorAdapter(applicationVirtualThreadExecutor);
    }
}
