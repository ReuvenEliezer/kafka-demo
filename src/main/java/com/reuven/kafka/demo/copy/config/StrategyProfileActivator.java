package com.reuven.kafka.demo.copy.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Bridges the single user-facing knob {@code copy.consumer.strategy} to the Spring profile
 * ({@code copy-inline} / {@code copy-staged}) that carries the matching
 * {@code spring.autoconfigure.exclude} list. {@code @ConditionalOnProperty} alone cannot keep the
 * inactive strategy's infrastructure out of the context (research.md R6).
 *
 * <p>Must run <b>before</b> {@link ConfigDataEnvironmentPostProcessor} — that is what actually loads
 * {@code application-copy-inline.yaml} / {@code application-copy-staged.yaml}, so the profile has to be
 * active before config-data processing runs, not merely before context refresh.
 */
public class StrategyProfileActivator implements EnvironmentPostProcessor, Ordered {

    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER - 1;

    private static final String STRATEGY_PROPERTY = "copy.consumer.strategy";
    private static final String STAGED = "staged";
    private static final String INLINE_PROFILE = "copy-inline";
    private static final String STAGED_PROFILE = "copy-staged";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String strategy = environment.getProperty(STRATEGY_PROPERTY, "inline");
        String profile = STAGED.equalsIgnoreCase(strategy) ? STAGED_PROFILE : INLINE_PROFILE;
        environment.addActiveProfile(profile);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
