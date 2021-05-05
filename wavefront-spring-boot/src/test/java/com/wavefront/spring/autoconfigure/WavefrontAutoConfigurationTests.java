package com.wavefront.spring.autoconfigure;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import brave.Tracer;
import com.wavefront.opentracing.WavefrontTracer;
import com.wavefront.opentracing.reporting.CompositeReporter;
import com.wavefront.opentracing.reporting.Reporter;
import com.wavefront.sdk.appagent.jvm.reporter.WavefrontJvmReporter;
import com.wavefront.sdk.common.Pair;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.entities.tracing.sampling.DurationSampler;
import com.wavefront.sdk.entities.tracing.sampling.RateSampler;
import com.wavefront.sdk.entities.tracing.sampling.Sampler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.wavefront.WavefrontMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.AbstractApplicationContextRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.cloud.sleuth.autoconfig.brave.BraveAutoConfiguration;
import org.springframework.cloud.sleuth.autoconfig.wavefront.WavefrontSleuthAutoConfiguration;
import org.springframework.cloud.sleuth.autoconfig.wavefront.WavefrontSleuthSpanHandler;
import org.springframework.cloud.sleuth.autoconfig.wavefront.WavefrontTracingCustomizer;
import org.springframework.core.Ordered;
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
class WavefrontAutoConfigurationTests {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(WavefrontAutoConfiguration.class, WavefrontTracingAutoConfiguration.class));

  @Test
  void applicationTagsIsConfiguredFromPropertiesWhenNoneExists() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app", "wavefront.application.service=test-service")
        .run((context) -> {
          assertThat(context).hasSingleBean(ApplicationTags.class);
          ApplicationTags tags = context.getBean(ApplicationTags.class);
          assertThat(tags.getApplication()).isEqualTo("test-app");
          assertThat(tags.getService()).isEqualTo("test-service");
          assertThat(tags.getCluster()).isNull();
          assertThat(tags.getShard()).isNull();
          assertThat(tags.getCustomTags()).isEmpty();
        });
  }

  @Test
  void applicationTagsCanBeCustomized() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app", "wavefront.application.service=test-service")
        .withBean(ApplicationTagsBuilderCustomizer.class,
            () -> (builder) -> builder.cluster("test-cluster").shard("test-shard"))
        .run((context) -> {
          assertThat(context).hasSingleBean(ApplicationTags.class);
          ApplicationTags tags = context.getBean(ApplicationTags.class);
          assertThat(tags.getApplication()).isEqualTo("test-app");
          assertThat(tags.getService()).isEqualTo("test-service");
          assertThat(tags.getCluster()).isEqualTo("test-cluster");
          assertThat(tags.getShard()).isEqualTo("test-shard");
          assertThat(tags.getCustomTags()).isEmpty();
        });
  }

  @Test
  void applicationTagsIsReusedWhenCustomInstanceExists() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app", "wavefront.application.service=test-service")
        .withBean(ApplicationTags.class,
            () -> new ApplicationTags.Builder("another-app", "another-service").build())
        .run((context) -> {
          assertThat(context).hasSingleBean(ApplicationTags.class);
          ApplicationTags tags = context.getBean(ApplicationTags.class);
          assertThat(tags.getApplication()).isEqualTo("another-app");
          assertThat(tags.getService()).isEqualTo("another-service");
          assertThat(tags.getCluster()).isNull();
          assertThat(tags.getShard()).isNull();
          assertThat(tags.getCustomTags()).isEmpty();
        });
  }

  @Test
  void applicationTagsAreExportedToWavefrontRegistry() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app",
            "wavefront.application.service=test-service")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class))).run((context) -> {
      MeterRegistry registry = context.getBean(MeterRegistry.class);
      registry.counter("my.counter", "env", "qa");
      assertThat(registry.find("my.counter").tags("env", "qa").tags("application", "test-app")
          .tags("service", "test-service").counter()).isNotNull();
    });
  }

  @Test
  void applicationTagsWithFullInformationAreExportedToWavefrontRegistry() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app",
            "wavefront.application.service=test-service", "wavefront.application.cluster=test-cluster",
            "wavefront.application.shard=test-shard")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class))).run((context) -> {
      MeterRegistry registry = context.getBean(MeterRegistry.class);
      registry.counter("my.counter", "env", "qa");
      assertThat(registry.find("my.counter").tags("env", "qa").tags("application", "test-app")
          .tags("service", "test-service").tags("cluster", "test-cluster").tags("shard", "test-shard")
          .counter()).isNotNull();
    });
  }

  @Test
  void applicationTagsAreNotExportedToNonWavefrontRegistry() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app", "wavefront.application.service=test-service")
        .with(metrics()).withConfiguration(AutoConfigurations.of(SimpleMetricsExportAutoConfiguration.class))
        .run((context) -> {
          MeterRegistry registry = context.getBean(MeterRegistry.class);
          registry.counter("my.counter", "env", "qa");
          assertThat(registry.find("my.counter").tags("env", "qa")).isNotNull();
          assertThat(registry.find("my.counter").tags("env", "qa").tags("application", "test-app")
              .tags("service", "test-service").tags("cluster", "test-cluster").tags("shard", "test-shard")
              .counter()).isNull();
        });
  }

  @Test
  void jvmReporterIsConfiguredWhenNoneExists() {
    this.contextRunner
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .run((context) -> assertThat(context).hasSingleBean(WavefrontJvmReporter.class));
  }

  @Test
  void jvmReporterCanBeDisabled() {
    this.contextRunner
        .withPropertyValues("wavefront.metrics.extract-jvm-metrics=false")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .run(context -> assertThat(context).doesNotHaveBean(WavefrontJvmReporter.class));
  }

  @Test
  void jvmReporterCanBeCustomized() {
    WavefrontJvmReporter reporter = mock(WavefrontJvmReporter.class);
    this.contextRunner
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .withBean(WavefrontJvmReporter.class, () -> reporter)
        .run((context) -> assertThat(context).getBean(WavefrontJvmReporter.class).isEqualTo(reporter));
  }

  @Test
  void jvmReporterNotConfiguredWithoutWavefrontSender() {
    this.contextRunner
        .with(metrics())
        .run(context -> assertThat(context).doesNotHaveBean(WavefrontJvmReporter.class));
  }

  @Test
  void tracingWithSleuthIsConfiguredWithWavefrontSender() {
    WavefrontSender sender = mock(WavefrontSender.class);
    this.contextRunner.withPropertyValues()
        .with(wavefrontMetrics(() -> sender))
        .with(sleuth())
        .run((context) -> {
          assertThat(context).hasSingleBean(WavefrontTracingCustomizer.class);
          WavefrontSleuthSpanHandler spanHandler = context.getBean(WavefrontSleuthSpanHandler.class);
          assertThat(spanHandler).hasFieldOrPropertyWithValue("wavefrontSender", sender);
        });
  }

  @Test
  void tracingWithSleuthWithEmptyEnvironmentUseDefaultTags() {
    this.contextRunner
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .with(sleuth())
        .run(assertSleuthSpanDefaultTags("unnamed_application", "unnamed_service"));
  }

  @Test
  void tracingWithSleuthWithWavefrontTagsAndSpringApplicationNameUseWavefrontTags() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=wavefront-application",
            "wavefront.application.service=wavefront-service", "spring.application.name=spring-service")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .with(sleuth())
        .run(assertSleuthSpanDefaultTags("wavefront-application", "wavefront-service"));
  }

  @Test
  void tracingWithSleuthWithSpringApplicationNameUseItRatherThanDefault() {
    this.contextRunner
        .withPropertyValues("spring.application.name=spring-service")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .with(sleuth())
        .run(assertSleuthSpanDefaultTags("unnamed_application", "spring-service"));
  }

  @Test
  void tracingWithSleuthWithCustomApplicationTagsUseThat() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=wavefront-application",
            "wavefront.application.service=wavefront-service")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .withBean(ApplicationTags.class, () -> new ApplicationTags.Builder(
            "custom-application", "custom-service").cluster("custom-cluster").shard("custom-shard").build())
        .with(sleuth())
        .run(assertSleuthSpanDefaultTags("custom-application", "custom-service", "custom-cluster", "custom-shard"));
  }

  @Test
  void tracingWithSleuthWithCustomApplicationTagsAndEmptyValuesFallbackToDefaults() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=wavefront-application",
            "wavefront.application.service=wavefront-service")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .withBean(ApplicationTags.class, () -> new ApplicationTags.Builder(
            "custom-application", "custom-service").build())
        .with(sleuth())
        .run(assertSleuthSpanDefaultTags("custom-application", "custom-service", "none", "none"));
  }

  private ContextConsumer<AssertableApplicationContext> assertSleuthSpanDefaultTags(String applicationName,
      String serviceName) {
    return assertSleuthSpanDefaultTags(applicationName, serviceName, "none", "none");
  }

  private ContextConsumer<AssertableApplicationContext> assertSleuthSpanDefaultTags(String applicationName,
      String serviceName, String cluster, String shard) {
    return (context) -> {
      assertThat(context).hasSingleBean(WavefrontTracingCustomizer.class);
      WavefrontSleuthSpanHandler spanHandler = context.getBean(WavefrontSleuthSpanHandler.class);
      List<Pair<String, String>> traceDerivedCustomTagKeys = (List<Pair<String, String>>) ReflectionTestUtils.getField(
              spanHandler, "defaultTags");
      assertThat(traceDerivedCustomTagKeys).contains(
          new Pair<>("application", applicationName),
          new Pair<>("service", serviceName),
          new Pair<>("cluster", cluster),
          new Pair<>("shard", shard));
    };
  }

  @Test
  void tracingWithOpenTracingBacksOffWhenSpringCloudSleuthIsAvailable() {
    this.contextRunner
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .with(sleuth())
        .run((context) -> assertThat(context).hasSingleBean(WavefrontTracingCustomizer.class).doesNotHaveBean(io.opentracing.Tracer.class));
  }

  @Test
  void tracingWithOpenTracingCanConfigureRateSampling() {
    this.contextRunner
        .withClassLoader(new FilteredClassLoader("org.springframework.cloud.sleuth"))
        .withPropertyValues("wavefront.tracing.opentracing.sampler.probability=0.1")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .run((context) -> {
          assertThat(context).hasSingleBean(io.opentracing.Tracer.class).hasSingleBean(WavefrontTracer.class);
          WavefrontTracer wavefrontTracer = context.getBean(WavefrontTracer.class);
          assertThat(getSamplers(wavefrontTracer)).singleElement().satisfies((sampler) -> {
            assertThat(sampler).isInstanceOf(RateSampler.class);
            assertThat(sampler).hasFieldOrPropertyWithValue("boundary", 1000L);
          });
        });
  }

  @Test
  void tracingWithOpenTracingCanConfigureDurationSampling() {
    this.contextRunner
        .withClassLoader(new FilteredClassLoader("org.springframework.cloud.sleuth"))
        .withPropertyValues("wavefront.tracing.opentracing.sampler.duration=2s")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .run((context) -> {
          assertThat(context).hasSingleBean(io.opentracing.Tracer.class).hasSingleBean(WavefrontTracer.class);
          WavefrontTracer wavefrontTracer = context.getBean(WavefrontTracer.class);
          assertThat(getSamplers(wavefrontTracer)).singleElement().satisfies((sampler) -> {
            assertThat(sampler).isInstanceOf(DurationSampler.class);
            assertThat(sampler).hasFieldOrPropertyWithValue("duration", 2000L);
          });
        });
  }

  @Test
  void tracingWithOpenTracingWithCustomReporters() {
    OrderedReporter firstReporter = mock(OrderedReporter.class);
    given(firstReporter.getOrder()).willReturn(5);
    OrderedReporter secondReporter = mock(OrderedReporter.class);
    given(secondReporter.getOrder()).willReturn(2);
    this.contextRunner
        .withClassLoader(new FilteredClassLoader("org.springframework.cloud.sleuth"))
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .withBean("firstReporter", Reporter.class, () ->  firstReporter)
        .withBean("secondReporter", Reporter.class, () ->  secondReporter)
        .run((context) -> {
          assertThat(context).hasSingleBean(io.opentracing.Tracer.class).hasSingleBean(WavefrontTracer.class);
          WavefrontTracer wavefrontTracer = context.getBean(WavefrontTracer.class);
          Reporter reporter = (Reporter) ReflectionTestUtils.getField(wavefrontTracer, "reporter");
          assertThat(reporter).isInstanceOf(CompositeReporter.class);
          assertThat(((CompositeReporter) reporter).getReporters())
              .containsExactly(secondReporter, firstReporter);
        });
  }

  @Test
  void tracingIsDisabledWhenOpenTracingAndSleuthAreNotAvailable() {
    this.contextRunner
        .withClassLoader(new FilteredClassLoader("org.springframework.cloud.sleuth", "io.opentracing"))
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .run((context) -> assertThat(context).doesNotHaveBean(WavefrontTracingCustomizer.class)
            .doesNotHaveBean(io.opentracing.Tracer.class));
  }

  @Test
  void tracingIsNotConfiguredWithNonWavefrontRegistry() {
    this.contextRunner.with(metrics()).run((context) -> assertThat(context).doesNotHaveBean(Tracer.class));
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

  @SuppressWarnings("unchecked")
  private static <T extends AbstractApplicationContextRunner<?, ?, ?>> Function<T, T> wavefrontMetrics(
      Supplier<WavefrontSender> wavefrontSender) {
    return (runner) -> (T) runner.withBean(WavefrontSender.class, wavefrontSender)
        .withConfiguration(AutoConfigurations.of(WavefrontMetricsExportAutoConfiguration.class))
        .with(metrics());
  }

  @SuppressWarnings("unchecked")
  private static <T extends AbstractApplicationContextRunner<?, ?, ?>> Function<T, T> metrics() {
    return (runner) -> (T) runner.withPropertyValues("management.metrics.use-global-registry=false")
        .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class));
  }

  @SuppressWarnings("unchecked")
  private static <T extends AbstractApplicationContextRunner<?, ?, ?>> Function<T, T> sleuth() {
    return (runner) -> (T) runner.withConfiguration(AutoConfigurations.of(BraveAutoConfiguration.class, WavefrontSleuthAutoConfiguration.class));
  }

  private interface OrderedReporter extends Reporter, Ordered {

  }

}
