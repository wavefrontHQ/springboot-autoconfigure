package com.wavefront.spring.autoconfigure;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;

/**
 * Advanced configuration properties for Wavefront.
 *
 * @author Stephane Nicoll
 */
@ConfigurationProperties("wavefront")
public class WavefrontProperties {

  /**
   * Whether the configured account is a freemium account. Can be enabled explicitly for
   * user-configured freemium accounts that do not have a user yet. Can be disabled
   * explicitly to prevent the account negotiation to kick-in.
   */
  private Boolean freemiumAccount;

  private final Application application = new Application();

  private final Metrics metrics = new Metrics();

  private final Tracing tracing = new Tracing();

  public Boolean getFreemiumAccount() {
    return this.freemiumAccount;
  }

  public void setFreemiumAccount(Boolean freemiumAccount) {
    this.freemiumAccount = freemiumAccount;
  }

  public Application getApplication() {
    return this.application;
  }

  public Metrics getMetrics() {
    return this.metrics;
  }

  public Tracing getTracing() {
    return this.tracing;
  }

  public static class Application {

    public static String DEFAULT_SERVICE_NAME = "unnamed_service";

    /**
     * Name of the application.
     */
    private String name = "unnamed_application";

    /**
     * Name of the service. If not specified, the value of "spring.application.name" is
     * used or "unnamed_service" as fallback.
     */
    private String service;

    /**
     * Cluster of the service.
     */
    private String cluster;

    /**
     * Shard of the service.
     */
    private String shard;

    public String getName() {
      return this.name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getService() {
      return this.service;
    }

    public void setService(String service) {
      this.service = service;
    }

    public String getCluster() {
      return this.cluster;
    }

    public void setCluster(String cluster) {
      this.cluster = cluster;
    }

    public String getShard() {
      return this.shard;
    }

    public void setShard(String shard) {
      this.shard = shard;
    }

  }

  public static class Metrics {

    /**
     * Extract JMV metrics.
     */
    private boolean extractJvmMetrics = true;

    public boolean isExtractJvmMetrics() {
      return this.extractJvmMetrics;
    }

    public void setExtractJvmMetrics(boolean extractJvmMetrics) {
      this.extractJvmMetrics = extractJvmMetrics;
    }

  }

  public static class Tracing {

    private boolean enabled;

    private final Opentracing opentracing = new Opentracing();

    /**
     * Tags that should be associated with RED metrics. If the span has any of the
     * specified tags, then those get reported to generated RED metrics.
     */
    private Set<String> redMetricsCustomTagKeys = new HashSet<>();

    public Opentracing getOpentracing() {
      return this.opentracing;
    }

    @DeprecatedConfigurationProperty(reason = "This feature is migrated to Spring Cloud Sleuth", replacement = "spring.sleuth.wavefront.red-metrics-custom-tag-keys")
    public Set<String> getRedMetricsCustomTagKeys() {
      return this.redMetricsCustomTagKeys;
    }

    public void setRedMetricsCustomTagKeys(Set<String> redMetricsCustomTagKeys) {
      this.redMetricsCustomTagKeys = redMetricsCustomTagKeys;
    }

    @DeprecatedConfigurationProperty(reason = "This feature is migrated to Spring Cloud Sleuth", replacement = "spring.sleuth.wavefront.enabled")
    public boolean isEnabled() {
      return this.enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * OpenTracing-specific settings.
     */
    public static class Opentracing {

      private final Sampler sampler = new Sampler();

      public Sampler getSampler() {
        return this.sampler;
      }

      public static class Sampler {

        /**
         * Probabilistic rate (between 0.0 and 1.0) of requests that should be sampled.
         * If not specified, probabilistic sampling is not applied.
         */
        private Double probability;

        /**
         * Spans longer than this duration are sampled. If not specified, duration
         * sampling is not applied.
         */
        private Duration duration;

        public Double getProbability() {
          return this.probability;
        }

        public void setProbability(Double probability) {
          this.probability = probability;
        }

        public Duration getDuration() {
          return this.duration;
        }

        public void setDuration(Duration duration) {
          this.duration = duration;
        }

      }

    }

  }

}
