package com.wavefront.spring.autoconfigure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import com.wavefront.opentracing.WavefrontTracer;
import com.wavefront.opentracing.reporting.CompositeReporter;
import com.wavefront.opentracing.reporting.Reporter;
import com.wavefront.opentracing.reporting.WavefrontSpanReporter;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.entities.tracing.sampling.DurationSampler;
import com.wavefront.sdk.entities.tracing.sampling.RateSampler;
import com.wavefront.sdk.entities.tracing.sampling.Sampler;
import io.micrometer.wavefront.WavefrontConfig;
import io.opentracing.Tracer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.cloud.sleuth.autoconfig.wavefront.WavefrontTracingCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A configuration for OpenTracing.
 *
 * @author Stephane Nicoll
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ Reporter.class, Tracer.class })
@ConditionalOnBean(WavefrontSender.class)
@ConditionalOnMissingBean(WavefrontTracingCustomizer.class)
class WavefrontTracingOpenTracingConfiguration {

  @Bean(destroyMethod = "flush")
  @ConditionalOnMissingBean(Tracer.class)
  WavefrontTracer wavefrontTracer(ApplicationTags applicationTags,
      WavefrontProperties wavefrontProperties, ObjectProvider<Reporter> reporters,
      ObjectProvider<WavefrontTracerBuilderCustomizer> customizers) {
    Reporter compositeReporter = createCompositeReporter(reporters.orderedStream());
    WavefrontTracer.Builder builder = new WavefrontTracer.Builder(compositeReporter, applicationTags)
        .excludeJvmMetrics();  // reported separately
    builder.redMetricsCustomTagKeys(
        new HashSet<>(wavefrontProperties.getTracing().getRedMetricsCustomTagKeys()));
    List<Sampler> samplers =
        createSamplers(wavefrontProperties.getTracing().getOpentracing().getSampler());
    samplers.forEach(builder::withSampler);
    customizers.orderedStream().forEach((customizer) -> customizer.customize(builder));
    return builder.build();
  }

  @Bean
  @ConditionalOnMissingBean(Reporter.class)
  Reporter wavefrontSpanReporter(WavefrontSender wavefrontSender, WavefrontConfig wavefrontConfig) {
    return new WavefrontSpanReporter.Builder().withSource(wavefrontConfig.source())
        .build(wavefrontSender);
  }

  private Reporter createCompositeReporter(Stream<Reporter> reporters) {
    Reporter[] reporterArray = reporters.toArray(Reporter[]::new);
    return (reporterArray.length > 1) ? new CompositeReporter(reporterArray) : reporterArray[0];
  }

  private List<Sampler> createSamplers(WavefrontProperties.Tracing.Opentracing.Sampler samplerProperties) {
    List<Sampler> samplers = new ArrayList<>();
    PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
    map.from(samplerProperties.getProbability()).to((probability) ->
        samplers.add(new RateSampler(probability)));
    map.from(samplerProperties.getDuration()).to((duration) ->
        samplers.add(new DurationSampler(duration.toMillis())));
    return samplers;
  }

}
