
package com.adc.demo;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@Profile("demo")
@EnableScheduling
@ConditionalOnProperty(name = "adc.demo.rolling.enabled", matchIfMissing = true)
class DemoScheduling {
    private final RollingDemoData rolling;
    private final Clock clock;

    DemoScheduling(RollingDemoData rolling, Clock clock) {
        this.rolling = rolling;
        this.clock = clock;
    }

    @EventListener
    void afterStartup(ApplicationReadyEvent event) {
        event.getApplicationContext().getBean(TaskScheduler.class).schedule(this::backfill, clock.instant());
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Shanghai")
    void seedToday() {
        rolling.seedToday();
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Shanghai")
    void backfill() {
        rolling.run();
    }

    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Shanghai")
    void issueDueBills() {
        rolling.issueDueBills();
    }
}
