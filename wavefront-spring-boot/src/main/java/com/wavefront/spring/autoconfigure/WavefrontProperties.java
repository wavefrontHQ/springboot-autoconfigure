package com.wavefront.spring.autoconfigure;

import java.util.HashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Advanced configuration properties for Wavefront.
 *
 * @author Stephane Nicoll
 */
@ConfigurationProperties("wavefront")
public class WavefrontProperties {

  /**
   * Whether a freemium account is active, so that a one time login URL is provided.
   */
  private boolean freemiumAccount;

  private final Application application = new Application();

  private final Tracing tracing = new Tracing();

  public boolean isFreemiumAccount() {
    return this.freemiumAccount;
  }

  public void setFreemiumAccount(boolean freemiumAccount) {
    this.freemiumAccount = freemiumAccount;
  }

  public Application getApplication() {
    return this.application;
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

  public static class Tracing {

    /**
     * Extract JMV metrics from traces.
     */
    private boolean extractJvmMetrics = true;

    /**
     * Tags that should be associated with RED metrics. If the span has any of the
     * specified tags, then those get reported to generated RED metrics.
     */
    private Set<String> redMetricsCustomTagKeys = new HashSet<>();

    public boolean isExtractJvmMetrics() {
      return this.extractJvmMetrics;
    }

    public void setExtractJvmMetrics(boolean extractJvmMetrics) {
      this.extractJvmMetrics = extractJvmMetrics;
    }

    public Set<String> getRedMetricsCustomTagKeys() {
      return this.redMetricsCustomTagKeys;
    }

    public void setRedMetricsCustomTagKeys(Set<String> redMetricsCustomTagKeys) {
      this.redMetricsCustomTagKeys = redMetricsCustomTagKeys;
    }

  }

}
