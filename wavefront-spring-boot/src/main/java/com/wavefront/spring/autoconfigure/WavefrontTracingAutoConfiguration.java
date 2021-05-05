package com.wavefront.spring.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.sleuth.autoconfig.wavefront.WavefrontSleuthAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * {@link EnableAutoConfiguration Auto-configuration} to integrate with Wavefront tracing.
 *
 * @author Marcin Grzejszczak
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(value = "wavefront.tracing.enabled", matchIfMissing = true)
@AutoConfigureAfter({WavefrontAutoConfiguration.class, WavefrontSleuthAutoConfiguration.class})
@Import(WavefrontTracingOpenTracingConfiguration.class)
public class WavefrontTracingAutoConfiguration {

}
