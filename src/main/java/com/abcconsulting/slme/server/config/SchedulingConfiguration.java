package com.abcconsulting.slme.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled tasks (the live-update keep-alive). */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {
}
