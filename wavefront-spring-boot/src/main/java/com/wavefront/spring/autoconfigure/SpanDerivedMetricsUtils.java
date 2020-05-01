package com.wavefront.spring.autoconfigure;

import com.wavefront.internal.reporter.WavefrontInternalReporter;
import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.sdk.common.Pair;
import com.wavefront.sdk.common.WavefrontSender;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wavefront.sdk.common.Constants.APPLICATION_TAG_KEY;
import static com.wavefront.sdk.common.Constants.CLUSTER_TAG_KEY;
import static com.wavefront.sdk.common.Constants.COMPONENT_TAG_KEY;
import static com.wavefront.sdk.common.Constants.HEART_BEAT_METRIC;
import static com.wavefront.sdk.common.Constants.SERVICE_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SHARD_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SOURCE_KEY;
import static com.wavefront.sdk.common.Constants.NULL_TAG_VAL;
import static com.wavefront.sdk.common.Utils.sanitizeWithoutQuotes;

import static io.opentracing.tag.Tags.SPAN_KIND;

/**
 * Util methods to generate data (metrics/histograms/heartbeats) from tracing spans
 * https://github.com/wavefrontHQ/wavefront-proxy/blob/master/proxy/src/main/java/com/wavefront/agent/listeners/tracing/SpanDerivedMetricsUtils.java
 * TODO: Move to Wavefront Internal Reporter (https://github.com/wavefrontHQ/wavefront-internal-reporter-java)
 *
 * @author Hao Song (songhao@vmware.com).
 */
public class SpanDerivedMetricsUtils {

  public final static String TRACING_DERIVED_PREFIX = "tracing.derived";
  private final static String INVOCATION_SUFFIX = ".invocation";
  private final static String ERROR_SUFFIX = ".error";
  private final static String DURATION_SUFFIX = ".duration.micros";
  private final static String TOTAL_TIME_SUFFIX = ".total_time.millis";
  private final static String OPERATION_NAME_TAG = "operationName";

  /**
   * Report generated metrics and histograms from the wavefront tracing span.
   *
   * @param operationName             span operation name.
   * @param application               name of the application.
   * @param service                   name of the service.
   * @param cluster                   name of the cluster.
   * @param shard                     name of the shard.
   * @param source                    reporting source.
   * @param componentTagValue         component tag value.
   * @param isError                   indicates if the span is erroneous.
   * @param spanDurationMicros        Original span duration (both Zipkin and Jaeger support micros
   *                                  duration).
   * @param traceDerivedCustomTagKeys custom tags added to derived RED metrics.
   * @param spanAnnotations           span tags.
   * @return HeartbeatMetricKey so that it is added to discovered keys.
   */
  static HeartbeatMetricKey reportWavefrontGeneratedData(
      WavefrontInternalReporter wfInternalReporter, String operationName, String application,
      String service, String cluster, String shard, String source, String componentTagValue,
      boolean isError, long spanDurationMicros, Set<String> traceDerivedCustomTagKeys,
      List<Pair<String, String>> spanAnnotations) {
    /*
     * 1) Can only propagate mandatory application/service and optional cluster/shard tags.
     * 2) Cannot convert ApplicationTags.customTags unfortunately as those are not well-known.
     * 3) Both Jaeger and Zipkin support error=true tag for erroneous spans
     */

    Map<String, String> pointTags = new HashMap<String, String>() {{
      put(APPLICATION_TAG_KEY, application);
      put(SERVICE_TAG_KEY, service);
      put(CLUSTER_TAG_KEY, cluster);
      put(SHARD_TAG_KEY, shard);
      put(OPERATION_NAME_TAG, operationName);
      put(COMPONENT_TAG_KEY, componentTagValue);
      put(SOURCE_KEY, source);
    }};

    /*
     * Use a separate customTagsMap and keep it separate from individual point tags, since
     * we do not propagate the original span component.
     */
    Map<String, String> customTags = new HashMap<>();
    if (traceDerivedCustomTagKeys.size() > 0) {
      spanAnnotations.forEach((annotation) -> {
        if (traceDerivedCustomTagKeys.contains(annotation._1)) {
          pointTags.put(annotation._1, annotation._2);
          customTags.put(annotation._1, annotation._2);
        }
      });
    }

    // tracing.derived.<application>.<service>.<operation>.invocation.count
    wfInternalReporter.newDeltaCounter(new MetricName(sanitizeWithoutQuotes(application +
        "." + service + "." + operationName + INVOCATION_SUFFIX), pointTags)).inc();

    if (isError) {
      // tracing.derived.<application>.<service>.<operation>.error.count
      wfInternalReporter.newDeltaCounter(new MetricName(sanitizeWithoutQuotes(application +
          "." + service + "." + operationName + ERROR_SUFFIX), pointTags)).inc();
    }

    // tracing.derived.<application>.<service>.<operation>.duration.micros.m
    if (isError) {
      Map<String, String> errorPointTags = new HashMap<>(pointTags);
      errorPointTags.put("error", "true");
      wfInternalReporter.newWavefrontHistogram(new MetricName(sanitizeWithoutQuotes(application +
          "." + service + "." + operationName + DURATION_SUFFIX), errorPointTags)).
          update(spanDurationMicros);
    } else {
      wfInternalReporter.newWavefrontHistogram(new MetricName(sanitizeWithoutQuotes(application +
          "." + service + "." + operationName + DURATION_SUFFIX), pointTags)).
          update(spanDurationMicros);
    }

    // tracing.derived.<application>.<service>.<operation>.total_time.millis.count
    wfInternalReporter.newDeltaCounter(new MetricName(sanitizeWithoutQuotes(application +
        "." + service + "." + operationName + TOTAL_TIME_SUFFIX), pointTags)).
        inc(spanDurationMicros / 1000);
    return new HeartbeatMetricKey(application, service, cluster, shard, source, customTags);
  }

  /**
   * Report discovered heartbeats to Wavefront.
   *
   * @param wavefrontSender            Wavefront sender via proxy.
   * @param discoveredHeartbeatMetrics Discovered heartbeats.
   */
  static void reportHeartbeats(String component,
                               WavefrontSender wavefrontSender,
                               Map<HeartbeatMetricKey, Boolean> discoveredHeartbeatMetrics) throws IOException {
    if (wavefrontSender == null) {
      // should never happen
      return;
    }
    Iterator<HeartbeatMetricKey> iter = discoveredHeartbeatMetrics.keySet().iterator();
    while (iter.hasNext()) {
      HeartbeatMetricKey key = iter.next();
      Map<String, String> tags = new HashMap<String, String>() {{
        put(APPLICATION_TAG_KEY, key.getApplication());
        put(SERVICE_TAG_KEY, key.getService());
        put(CLUSTER_TAG_KEY, key.getCluster());
        put(SHARD_TAG_KEY, key.getShard());
        put(COMPONENT_TAG_KEY, component);
        putAll(key.getCustomTags());
      }};
      tags.putIfAbsent(SPAN_KIND.getKey(), NULL_TAG_VAL);
      wavefrontSender.sendMetric(HEART_BEAT_METRIC, 1.0, System.currentTimeMillis(),
          key.getSource(), tags);
      // remove from discovered list so that it is only reported on subsequent discovery
      discoveredHeartbeatMetrics.remove(key);
    }
  }
}
