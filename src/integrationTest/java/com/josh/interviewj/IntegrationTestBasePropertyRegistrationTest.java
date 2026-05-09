package com.josh.interviewj;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTestBasePropertyRegistrationTest {

    @Test
    void registersRabbitMqPropertiesNeededBySpringBootTests() {
        Map<String, Supplier<Object>> properties = new LinkedHashMap<>();
        DynamicPropertyRegistry registry = new DynamicPropertyRegistry() {
            @Override
            public void add(String name, Supplier<Object> valueSupplier) {
                properties.put(name, valueSupplier);
            }
        };

        IntegrationTestBase.registerProperties(registry);

        assertThat(properties.keySet()).contains(
                "app.mq.resume-parse.exchange",
                "app.mq.resume-parse.queue",
                "app.mq.resume-parse.routing-key",
                "app.mq.resume-parse.dlx",
                "app.mq.resume-parse.dlq",
                "app.mq.resume-parse.dlq-routing-key",
                "app.mq.kb-doc.exchange",
                "app.mq.kb-doc.queue",
                "app.mq.kb-doc.routing-key",
                "app.mq.kb-doc.dlx",
                "app.mq.kb-doc.dlq",
                "app.mq.kb-doc.dlq-routing-key",
                "app.mq.resume-analysis.exchange",
                "app.mq.resume-analysis.queue",
                "app.mq.resume-analysis.routing-key",
                "app.mq.resume-analysis.dlx",
                "app.mq.resume-analysis.dlq",
                "app.mq.resume-analysis.dlq-routing-key",
                "app.outbox.max-retries",
                "app.outbox.processing-timeout"
        );
        assertThat(properties.get("app.mq.resume-parse.exchange").get()).isEqualTo("resume.parse.exchange");
        assertThat(properties.get("app.mq.kb-doc.queue").get()).isEqualTo("kb.doc.queue");
        assertThat(properties.get("app.mq.resume-analysis.routing-key").get()).isEqualTo("resume.analysis");
        assertThat(properties.get("app.outbox.processing-timeout").get()).isEqualTo("30000");
    }
}
