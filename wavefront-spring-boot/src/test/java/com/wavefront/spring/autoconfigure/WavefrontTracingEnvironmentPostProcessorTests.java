package com.wavefront.spring.autoconfigure;

import org.junit.jupiter.api.Test;

import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

import static com.wavefront.spring.autoconfigure.WavefrontTracingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME;
import static org.assertj.core.api.BDDAssertions.then;

class WavefrontTracingEnvironmentPostProcessorTests {

	MockEnvironment environment = new MockEnvironment();

	WavefrontTracingEnvironmentPostProcessor processor = new WavefrontTracingEnvironmentPostProcessor();

	@Test
	void postProcessorDoesNothingWhenExecutedInBootstrapPhase() {
		environment.getPropertySources().addFirst(bootstrapPropertySource());

		new WavefrontTracingEnvironmentPostProcessor().postProcessEnvironment(environment, null);

		then(environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)).isFalse();
	}

	@Test
	void newPropertiesAreSetWhenLegacyOnesAreUsed() {
		environment.setProperty("wavefront.tracing.red-metrics-custom-tag-keys", "red-metrics-custom-tag-keys");
		environment.setProperty("wavefront.tracing.enabled", "true");

		processor.postProcessEnvironment(environment, null);

		then(environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)).as("New property source added").isTrue();
		PropertySource<?> propertySource = environment.getPropertySources().get(PROPERTY_SOURCE_NAME);
		thenNewPropertySetWithLegacyValue(propertySource, "spring.sleuth.wavefront.red-metrics-custom-tag-keys", "red-metrics-custom-tag-keys");
		thenNewPropertySetWithLegacyValue(propertySource, "spring.sleuth.wavefront.enabled", "true");
	}

	private void thenNewPropertySetWithLegacyValue(PropertySource<?> propertySource, String newKey, String oldValue) {
		then(propertySource.containsProperty(newKey)).as("New property set").isTrue();
		then(propertySource.getProperty(newKey)).as("New property set with legacy property value").isEqualTo(oldValue);
	}

	@Test
	void newPropertiesAreSetWhenLegacyOnesAreUsedWithoutHyphens() {
		environment.setProperty("wavefront.tracing.redMetricsCustomTagKeys", "red-metrics-custom-tag-keys");

		processor.postProcessEnvironment(environment, null);

		then(environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)).as("New property source added").isTrue();
		then(environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)).as("New property source added").isTrue();
		PropertySource<?> propertySource = environment.getPropertySources().get(PROPERTY_SOURCE_NAME);
		thenNewPropertySetWithLegacyValue(propertySource, "spring.sleuth.wavefront.red-metrics-custom-tag-keys", "red-metrics-custom-tag-keys");
	}

	private PropertySource<Object> bootstrapPropertySource() {
		return new PropertySource<Object>("bootstrap") {
			@Override
			public Object getProperty(String s) {
				return null;
			}
		};
	}
}