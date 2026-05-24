package com.assessment.igirepay.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration class that enables Spring's scheduling support.
 * <p>
 * This allows the application to detect and execute methods
 * annotated with @Scheduled.
 * <p>
 * Without this configuration, scheduled tasks such as
 * key expiration cleanup jobs will not run.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

}

