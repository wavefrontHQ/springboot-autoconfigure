package com.wavefront.spring.autoconfigure;

import brave.TracingCustomizer;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.common.application.ApplicationTags;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.wavefront.WavefrontConfig;
import org.aspectj.weaver.tools.PointcutPrimitive;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.sleuth.LocalServiceName;
import org.springframework.cloud.sleuth.SpanNamer;
import org.springframework.cloud.sleuth.autoconfig.TraceAutoConfiguration;
import org.springframework.cloud.sleuth.sampler.SamplerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Configuration for Wavefront tracing using Spring Cloud Sleuth.
 *
 * @author Adrian Cole
 * @author Stephane Nicoll
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ SpanNamer.class, TracingCustomizer.class, PointcutPrimitive.class })
@AutoConfigureBefore(TraceAutoConfiguration.class)
// This import is required and can be removed in a future release of Spring Cloud Sleuth
@Import(SamplerAutoConfiguration.class)
class WavefrontTracingSleuthConfiguration {

  static final String BEAN_NAME = "wavefrontTracingCustomizer";

  /**
   * Wavefront use a combination of null and non-values in defaults. Some non-values are not defined
   * by constants. This constant helps reduce drift in non-value comparison.
   */
  private static final String DEFAULT_SERVICE = new WavefrontProperties().getApplication().getService();

  @Bean(BEAN_NAME)
  @ConditionalOnMissingBean(name = BEAN_NAME)
  @ConditionalOnBean({ MeterRegistry.class, WavefrontConfig.class, WavefrontSender.class })
  TracingCustomizer wavefrontTracingCustomizer(MeterRegistry meterRegistry,
                                               WavefrontSender wavefrontSender,
                                               ApplicationTags applicationTags,
                                               WavefrontConfig wavefrontConfig,
                                               WavefrontProperties wavefrontProperties,
                                               @LocalServiceName String localServiceName) {
    WavefrontSleuthSpanHandler spanHandler = new WavefrontSleuthSpanHandler(
        // https://github.com/wavefrontHQ/wavefront-opentracing-sdk-java/blob/f1f08d8daf7b692b9b61dcd5bc24ca6befa8e710/src/main/java/com/wavefront/opentracing/reporting/WavefrontSpanReporter.java#L54
        50000, // TODO: maxQueueSize should be a property, ya?
        wavefrontSender,
        meterRegistry,
        wavefrontConfig.source(),
        applicationTags,
        wavefrontProperties,
        localServiceName
    );

    return t -> t.traceId128Bit(true).supportsJoin(false).addFinishedSpanHandler(spanHandler);
  }

}
