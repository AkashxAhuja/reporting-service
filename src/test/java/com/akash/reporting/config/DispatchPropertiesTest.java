package com.akash.reporting.config;

import java.time.Duration;

import com.akash.reporting.support.DispatchTestSettings;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchPropertiesTest {

    @Test
    void rejectsALeaseThatCannotOutliveTheConfirmationWait() {
        var defaults = DispatchTestSettings.defaults();
        var invalid = new DispatchProperties(true, defaults.pollInterval(), Duration.ofMillis(10),
                defaults.confirmTimeout(), defaults.retryDelay(), defaults.batchSize(),
                defaults.exchange(), defaults.queue(), defaults.routingKey());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(invalid)).isNotEmpty();
            assertThat(factory.getValidator().validate(defaults)).isEmpty();
        }
    }
}
