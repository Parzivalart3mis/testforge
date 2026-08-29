package com.helios.testforge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pool that runs provisioning pipelines.
 *
 * <p>Deliberately a bounded platform-thread pool rather than virtual threads:
 * a pipeline is not IO-bound waiting on many sockets, it is a long CPU-and-JDBC
 * job that holds a connection to the ephemeral cluster for its whole duration.
 * Bounding the pool bounds concurrent load on that cluster, which is the actual
 * scarce resource.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "provisioningExecutor", destroyMethod = "shutdown")
    ExecutorService provisioningExecutor(TestForgeProperties properties) {
        int workers = properties.jobs().workers();
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "tf-provision-" + counter.getAndIncrement());
                thread.setDaemon(false);
                return thread;
            }
        };
        return Executors.newFixedThreadPool(workers, factory);
    }
}
