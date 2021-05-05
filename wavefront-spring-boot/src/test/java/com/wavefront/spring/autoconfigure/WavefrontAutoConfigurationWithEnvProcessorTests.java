package com.wavefront.spring.autoconfigure;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.wavefront.opentracing.WavefrontTracer;
import com.wavefront.opentracing.reporting.Reporter;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.entities.tracing.sampling.Sampler;
import org.junit.jupiter.api.Test;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.cloud.sleuth.autoconfig.wavefront.WavefrontSleuthSpanHandler;
import org.springframework.cloud.sleuth.autoconfig.wavefront.WavefrontTracingCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link WavefrontAutoConfiguration}.
 *
 * @author Stephane Nicoll
 * @author Tommy Ludwig
 */
class WavefrontAutoConfigurationWithEnvProcessorTests {

  SpringApplicationBuilder contextRunner = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE);

  @Test
  void tracingCanBeDisabled() {
    AssertableApplicationContext context = AssertableApplicationContext.get(() -> this.contextRunner
            .run("--spring.sleuth.enabled=false", "--wavefront.tracing.enabled=false"));

    assertThat(context).doesNotHaveBean(WavefrontTracingCustomizer.class)
            .doesNotHaveBean(io.opentracing.Tracer.class);
  }

  @SuppressWarnings("unchecked")
  @Test
  void tracingWithSleuthCanBeConfigured() {
    AssertableApplicationContext context = AssertableApplicationContext.get(() -> this.contextRunner
            .run("--wavefront.tracing.red-metrics-custom-tag-keys=region,test"));

    assertThat(context).hasSingleBean(WavefrontTracingCustomizer.class);
    WavefrontSleuthSpanHandler spanHandler = context.getBean(WavefrontSleuthSpanHandler.class);
    Set<String> traceDerivedCustomTagKeys = (Set<String>) ReflectionTestUtils.getField(
            spanHandler, "traceDerivedCustomTagKeys");
    assertThat(traceDerivedCustomTagKeys).containsExactlyInAnyOrder("region", "test");
  }

  @Test
  void tracingWithOpenTracingCanBeConfiguredWhenSleuthIsNotAvailable() {
    AssertableApplicationContext context = AssertableApplicationContext.get(() -> this.contextRunner
            .run("--spring.sleuth.enabled=false", "--wavefront.tracing.red-metrics-custom-tag-keys=region,test"));

    assertThat(context).hasSingleBean(io.opentracing.Tracer.class).hasSingleBean(WavefrontTracer.class);
    WavefrontTracer wavefrontTracer = context.getBean(WavefrontTracer.class);
    Reporter reporter = (Reporter) ReflectionTestUtils.getField(wavefrontTracer, "reporter");
    assertThat(reporter.getFailureCount()).isEqualTo(42);
    assertThat(getRedMetricsCustomTagKeys(wavefrontTracer))
            .containsExactlyInAnyOrder("span.kind", "region", "test");
    assertThat(getSamplers(wavefrontTracer)).isEmpty();
  }

  @SuppressWarnings("unchecked")
  private Set<String> getRedMetricsCustomTagKeys(WavefrontTracer wavefrontTracer) {
    return (Set<String>) ReflectionTestUtils.getField(wavefrontTracer,
            "redMetricsCustomTagKeys");
  }

  @SuppressWarnings("unchecked")
  private List<Sampler> getSamplers(WavefrontTracer wavefrontTracer) {
    return (List<Sampler>) ReflectionTestUtils.getField(wavefrontTracer,
            "samplers");
  }

  @Test
  void tracingWithOpenTracingInvokeWavefrontTracerBuilderCustomizer() {
    AssertableApplicationContext context = AssertableApplicationContext.get(() -> this.contextRunner
            .run("--spring.sleuth.enabled=false", "--test.customizer.enabled=true", "--wavefront.tracing.red-metrics-custom-tag-keys=region,test"));

    assertThat(context).hasSingleBean(io.opentracing.Tracer.class).hasSingleBean(WavefrontTracer.class);
    WavefrontTracer wavefrontTracer = context.getBean(WavefrontTracer.class);
    assertThat(getRedMetricsCustomTagKeys(wavefrontTracer))
            .containsExactlyInAnyOrder("span.kind", "region", "test", "customized");
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class Config {

    @Bean
    WavefrontSender mockWavefrontSender() {
      WavefrontSender sender = mock(WavefrontSender.class);
      given(sender.getFailureCount()).willReturn(42);
      return sender;
    }

    @Bean
    @ConditionalOnProperty(value = "test.customizer.enabled", havingValue = "true")
    WavefrontTracerBuilderCustomizer wavefrontTracerBuilderCustomizer() {
      return builder -> builder.redMetricsCustomTagKeys(Collections.singleton("customized"));
    }
  }

}
