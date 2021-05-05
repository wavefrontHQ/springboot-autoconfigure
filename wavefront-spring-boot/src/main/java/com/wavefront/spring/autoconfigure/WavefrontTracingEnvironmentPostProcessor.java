package com.wavefront.spring.autoconfigure;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * Maps from Wavefront to Sleuth properties since wavefront tracing ones will be deprecated
 * and moved to Sleuth.
 *
 * @author Marcin Grzejszczak
 * @since 2.2.0
 */
class WavefrontTracingEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String PROPERTY_SOURCE_NAME = "wavefrontDefaultProperties";

	final Map<String, String> oldPropertyToNewMapping = new HashMap<>();

	WavefrontTracingEnvironmentPostProcessor() {
		this.oldPropertyToNewMapping.put("wavefront.tracing.redMetricsCustomTagKeys", "spring.sleuth.wavefront.red-metrics-custom-tag-keys");
		this.oldPropertyToNewMapping.put("wavefront.tracing.red-metrics-custom-tag-keys", "spring.sleuth.wavefront.red-metrics-custom-tag-keys");
		this.oldPropertyToNewMapping.put("wavefront.tracing.enabled", "spring.sleuth.wavefront.enabled");
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (environment.getPropertySources().contains("bootstrap")) {
			// Do not run in the bootstrap phase as the user configuration is not available yet
			return;
		}
		Map<String, Object> propertyMap = new HashMap<>();
		oldPropertyToNewMapping.forEach((key, value) -> useNewPropertyWhenOldOnePresent(environment, propertyMap, key, value));
		if (!propertyMap.isEmpty()) {
			MapPropertySource target = new MapPropertySource(PROPERTY_SOURCE_NAME, propertyMap);
			environment.getPropertySources().addLast(target);
		}
	}

	private void useNewPropertyWhenOldOnePresent(Environment environment, Map<String, Object> map, String oldPropName, String newPropName) {
		Object oldPropValue = environment.getProperty(oldPropName);
		if (oldPropValue == null) {
			return;
		}
		map.put(newPropName, oldPropValue);
	}

}
