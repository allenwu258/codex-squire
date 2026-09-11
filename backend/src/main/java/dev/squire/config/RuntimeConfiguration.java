package dev.squire.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration
public class RuntimeConfiguration {
    @Bean(destroyMethod = "dispose")
    public Scheduler databaseScheduler() {
        return Schedulers.newBoundedElastic(4, 1024, "sqlite");
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
