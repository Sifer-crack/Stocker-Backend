package com.stocker.inventory.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** AGENTS.md: the Kafka consumer group id is stocker.&lt;service&gt; (inventory must not share analytics' group). */
class KafkaGroupIdConfigTest {

    @Test
    void consumerGroupIsStockerInventory() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        assertThat(yaml.getObject().getProperty("spring.kafka.consumer.group-id")).isEqualTo("stocker.inventory");
    }
}
