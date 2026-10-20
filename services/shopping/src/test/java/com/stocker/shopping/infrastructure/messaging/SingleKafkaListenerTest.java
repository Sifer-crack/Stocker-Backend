package com.stocker.shopping.infrastructure.messaging;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Kafka starts one consumer per @KafkaListener, and consumers in the same group split the partitions between
 * them. A second (no-op) listener in shopping's group would silently swallow part of the price events.
 */
class SingleKafkaListenerTest {

	@Test
	void shoppingHasExactlyOneKafkaListener() throws Exception {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
		List<String> listeners = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents("com.stocker.shopping")) {
			Class<?> type = Class.forName(definition.getBeanClassName());
			for (Method method : type.getDeclaredMethods()) {
				if (AnnotatedElementUtils.hasAnnotation(method, KafkaListener.class)) {
					listeners.add(type.getSimpleName() + "#" + method.getName());
				}
			}
		}

		assertThat(listeners).containsExactly("KafkaConsumerConfig#onEvent");
	}
}
