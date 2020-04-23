package com.wavefront.spring.autoconfigure;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import brave.handler.FinishedSpanHandler;
import brave.handler.MutableSpan;
import brave.handler.MutableSpan.AnnotationConsumer;
import brave.handler.MutableSpan.TagConsumer;
import brave.propagation.TraceContext;
import com.wavefront.sdk.common.Pair;
import com.wavefront.sdk.entities.tracing.SpanLog;
import com.wavefront.sdk.entities.tracing.WavefrontTracingSpanSender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import static com.wavefront.sdk.common.Constants.APPLICATION_TAG_KEY;
import static com.wavefront.sdk.common.Constants.DEBUG_TAG_KEY;
import static com.wavefront.sdk.common.Constants.ERROR_TAG_KEY;
import static com.wavefront.sdk.common.Constants.NULL_TAG_VAL;
import static com.wavefront.sdk.common.Constants.SERVICE_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SOURCE_KEY;
import static com.wavefront.sdk.common.Constants.SPAN_LOG_KEY;

/**
 * This converts a span recorded by Brave and invokes {@link WavefrontTracingSpanSender#sendSpan}.
 *
 * <p>This uses a combination of conversion approaches from Wavefront projects:
 * <ul>
 *   <li>https://github.com/wavefrontHQ/wavefront-opentracing-sdk-java</li>
 *   <li>https://github.com/wavefrontHQ/wavefront-proxy</li>
 * </ul>
 *
 * <p>On conflict, we make a comment and prefer wavefront-opentracing-sdk-java. The rationale is
 * wavefront-opentracing-sdk-java uses the same {@link WavefrontTracingSpanSender#sendSpan} library,
 * so it is easier to reason with. This policy can be revisited by future maintainers.
 *
 * <p><em>Note:</em>UUID conversions follow the same conventions used in practice in Wavefront.
 * Ex. https://github.com/wavefrontHQ/wavefront-opentracing-sdk-java/blob/6babf2ff95daa37452e1e8c35ae54b58b6abb50f/src/main/java/com/wavefront/opentracing/propagation/JaegerWavefrontPropagator.java#L191-L204
 * While in practice this is not a problem, it is worth mentioning that this convention will only
 * only result in RFC 4122 timestamp (version 1) format by accident. In other words, don't call
 * {@link UUID#timestamp()} on UUIDs converted here, or in other Wavefront code, as it might
 * throw.
 */
final class WavefrontSpanHandler extends FinishedSpanHandler implements Runnable, Closeable {
  private static final Log LOG = LogFactory.getLog(WavefrontSpanHandler.class);
  // https://github.com/wavefrontHQ/wavefront-proxy/blob/3dd1fa11711a04de2d9d418e2269f0f9fb464f36/proxy/src/main/java/com/wavefront/agent/listeners/tracing/ZipkinPortUnificationHandler.java#L114-L114
  private static final String DEFAULT_SPAN_NAME = "defaultOperation";
  private static final String INVOCATION_SUFFIX = ".invocation";
  private static final String ERROR_SUFFIX = ".error";
  private static final String TOTAL_TIME_SUFFIX = ".total_time.millis";
  private static final String DURATION_SUFFIX = ".duration.micros";
  private static final String OPERATION_NAME_TAG = "operationName";
  private static final String HTTP_STATUS_CODE = "http.status_code";
  private static final Pattern WHITESPACE = Pattern.compile("[\\s]+");

  /** for RED metrics */
  @Override public boolean alwaysSampleLocal() {
    return true;
  }

  final WavefrontTracingSpanSender wavefrontSender;
  final LinkedBlockingQueue<Pair<TraceContext, MutableSpan>> spanBuffer;

  final String source;
  final List<Pair<String, String>> defaultTags;
  final Set<String> defaultTagKeys;

  final MeterRegistry meterRegistry;
  final Set<String> redMetricsCustomTagKeys;
  final String redMetricNamePrefix;

  final Counter spansDropped;
  final Counter spansReceived;
  final Counter reportErrors;

  final Thread sendingThread;
  private volatile boolean stop = false;

  WavefrontSpanHandler(int maxQueueSize, WavefrontTracingSpanSender wavefrontSender,
      MeterRegistry meterRegistry, String source, List<Pair<String, String>> defaultTags) {
    this.wavefrontSender = wavefrontSender;
    this.spanBuffer = new LinkedBlockingQueue<>(maxQueueSize);

    this.meterRegistry = meterRegistry;
    this.source = source;
    this.defaultTags = defaultTags;
    this.defaultTagKeys = defaultTags.stream().map(p -> p._1).collect(Collectors.toSet());
    this.defaultTagKeys.add(SOURCE_KEY);

    this.redMetricsCustomTagKeys = Collections.emptySet(); // TODO
    String application = null, service = null;
    for (Pair<String, String> defaultTag : defaultTags) {
      if (defaultTag._1.equals(APPLICATION_TAG_KEY)) application = defaultTag._2;
      if (defaultTag._1.equals(SERVICE_TAG_KEY)) service = defaultTag._2;
    }
    // Need to sanitize metric name as application, service and operation names can have spaces
    // and other invalid metric name characters
    redMetricNamePrefix = sanitize(application + "." + service + ".");

    // init internal metrics
    meterRegistry.gauge("reporter.queue.size", spanBuffer, sb -> (double) sb.size());
    meterRegistry.gauge("reporter.queue.remaining_capacity", spanBuffer,
        sb -> (double) sb.remainingCapacity());
    this.spansReceived = meterRegistry.counter("reporter.spans.received");
    this.spansDropped = meterRegistry.counter("reporter.spans.dropped");
    this.reportErrors = meterRegistry.counter("reporter.errors");

    this.sendingThread = new Thread(this, "wavefrontSpanReporter");
    this.sendingThread.setDaemon(true);
    this.sendingThread.start();
  }

  // Simplified variant of WavefrontSpan, which appears to collect 100% data, but
  // conditionally sample traces
  // https://github.com/wavefrontHQ/wavefront-opentracing-sdk-java/blob/dfac3c7c7a730885e021263e7d2e4f9c226ea9e9/src/main/java/com/wavefront/opentracing/WavefrontSpan.java#L245-L269
  @Override public boolean handle(TraceContext context, MutableSpan span) {
    if (span.finishTimestamp() != 0L && span.startTimestamp() != 0L) {
      reportWavefrontGeneratedData(context, span);
    }

    if (!context.sampled()) return true;

    // Below here is the same behavior as WavefrontSpanReporter
    // https://github.com/wavefrontHQ/wavefront-opentracing-sdk-java/blob/f1f08d8daf7b692b9b61dcd5bc24ca6befa8e710/src/main/java/com/wavefront/opentracing/reporting/WavefrontSpanReporter.java#L163-L179
    spansReceived.increment();
    if (!spanBuffer.offer(Pair.of(context, span))) {
      spansDropped.increment();
      if (LOG.isWarnEnabled()) {
        LOG.warn("Buffer full, dropping span: " + span);
        LOG.warn("Total spans dropped: " + spansDropped.count());
      }
    }
    return true; // regardless of error, other handlers should run
  }

  // https://github.com/wavefrontHQ/wavefront-opentracing-sdk-java/blob/dfac3c7c7a730885e021263e7d2e4f9c226ea9e9/src/main/java/com/wavefront/opentracing/WavefrontTracer.java#L259-L326
  void reportWavefrontGeneratedData(TraceContext context, MutableSpan span) {
    // NOTE: We expect the meter registry to already have default tags like cluster, service, etc.
    List<Tag> pointTags = new ArrayList<>();
    span.forEachTag((l, k, v) -> {
      if (redMetricsCustomTagKeys.contains(k)) {
        l.add(Tag.of(k, v));
      }
    }, pointTags);

    String kind = span.kind() != null ? span.kind().name() : NULL_TAG_VAL;
    pointTags.add(Tag.of("span.kind", kind));

    boolean isError = span.error() != null || span.tag("error") != null;
    String httpStatus = span.tag(HTTP_STATUS_CODE);
    Tag httpStatusTag = httpStatus != null ? Tag.of(HTTP_STATUS_CODE, httpStatus) : null;
    // Propagate HTTP status if the span has error
    if (httpStatusTag != null && isError) {
      pointTags.add(httpStatusTag);
    }

    // Add operation tag after sending RED heartbeat.
    pointTags.add(Tag.of(OPERATION_NAME_TAG, span.name()));

    String metricNamePrefix = redMetricNamePrefix + sanitize(span.name());
    if (isError) {
      meterRegistry.counter(metricNamePrefix + ERROR_SUFFIX, pointTags).increment();
    }

    // Remove http error status before sending request and duration metrics
    if (httpStatusTag != null) pointTags.remove(httpStatusTag);
    meterRegistry.counter(metricNamePrefix + INVOCATION_SUFFIX, pointTags).increment();

    long spanDurationMicros = span.finishTimestamp() - span.startTimestamp();
    // Convert from micros to millis and add to duration counter
    meterRegistry.counter(metricNamePrefix + TOTAL_TIME_SUFFIX, pointTags)
        .increment((double) spanDurationMicros / 1000L);

    if (isError) pointTags.add(Tag.of(ERROR_TAG_KEY, "true"));
    // Support duration in microseconds instead of milliseconds
    meterRegistry.summary(metricNamePrefix + DURATION_SUFFIX, pointTags)
        .record(spanDurationMicros);
  }

  // https://github.com/wavefrontHQ/wavefront-opentracing-sdk-java/blob/dfac3c7c7a730885e021263e7d2e4f9c226ea9e9/src/main/java/com/wavefront/opentracing/WavefrontTracer.java#L334-L342
  static String sanitize(String s) {
    final String whitespaceSanitized = WHITESPACE.matcher(s).replaceAll("-");
    if (s.contains("\"") || s.contains("'")) {
      // for single quotes, once we are double-quoted, single quotes can exist happily inside it.
      return whitespaceSanitized.replaceAll("\"", "\\\\\"");
    } else {
      return whitespaceSanitized;
    }
  }

  private void send(TraceContext context, MutableSpan span) {
    UUID traceId = new UUID(context.traceIdHigh(), context.traceId());
    UUID spanId = new UUID(0L, context.spanId());

    // NOTE: wavefront-opentracing-sdk-java and wavefront-proxy differ, but we prefer the former.
    // https://github.com/wavefrontHQ/wavefront-opentracing-sdk-java/blob/f1f08d8daf7b692b9b61dcd5bc24ca6befa8e710/src/main/java/com/wavefront/opentracing/reporting/WavefrontSpanReporter.java#L187-L190
    // https://github.com/wavefrontHQ/wavefront-proxy/blob/3dd1fa11711a04de2d9d418e2269f0f9fb464f36/proxy/src/main/java/com/wavefront/agent/listeners/tracing/ZipkinPortUnificationHandler.java#L248-L252
    List<UUID> parents = null;
    if (context.parentIdAsLong() != 0L) {
      parents = Collections.singletonList(new UUID(0L, context.parentIdAsLong()));
    }
    List<UUID> followsFrom = null;

    // https://github.com/wavefrontHQ/wavefront-proxy/blob/3dd1fa11711a04de2d9d418e2269f0f9fb464f36/proxy/src/main/java/com/wavefront/agent/listeners/tracing/ZipkinPortUnificationHandler.java#L344-L345
    String name = span.name();
    if (name == null) name = DEFAULT_SPAN_NAME;

    // Start and duration become 0L if unset. Any positive duration rounds up to 1 millis.
    long startMillis = span.startTimestamp() / 1000L, finishMillis = span.finishTimestamp() / 1000L;
    long durationMillis = startMillis != 0 && finishMillis != 0L ? Math.max(finishMillis - startMillis, 1L) : 0L;

    WavefrontConsumer wavefrontConsumer = new WavefrontConsumer(defaultTagKeys);

    // https://github.com/wavefrontHQ/wavefront-proxy/blob/3dd1fa11711a04de2d9d418e2269f0f9fb464f36/proxy/src/main/java/com/wavefront/agent/listeners/tracing/ZipkinPortUnificationHandler.java#L397-L402
    List<SpanLog> spanLogs = new ArrayList<>();
    span.forEachAnnotation(wavefrontConsumer, spanLogs);

    List<Pair<String, String>> tags = new ArrayList<>(defaultTags);
    span.forEachTag(wavefrontConsumer, tags);

    // https://github.com/wavefrontHQ/wavefront-proxy/blob/3dd1fa11711a04de2d9d418e2269f0f9fb464f36/proxy/src/main/java/com/wavefront/agent/listeners/tracing/ZipkinPortUnificationHandler.java#L300-L303
    if (context.debug() || wavefrontConsumer.debug) {
      tags.add(Pair.of(DEBUG_TAG_KEY, "true"));
    }

    // https://github.com/wavefrontHQ/wavefront-proxy/blob/3dd1fa11711a04de2d9d418e2269f0f9fb464f36/proxy/src/main/java/com/wavefront/agent/listeners/tracing/ZipkinPortUnificationHandler.java#L254-L266
    if (span.kind() != null) {
      String kind = span.kind().toString().toLowerCase();
      tags.add(Pair.of("span.kind", kind));
      if (wavefrontConsumer.hasAnnotations) {
        tags.add(Pair.of("_spanSecondaryId", kind));
      }
    }

    // https://github.com/wavefrontHQ/wavefront-proxy/blob/3dd1fa11711a04de2d9d418e2269f0f9fb464f36/proxy/src/main/java/com/wavefront/agent/listeners/tracing/ZipkinPortUnificationHandler.java#L329-L332
    if (wavefrontConsumer.hasAnnotations) {
      tags.add(Pair.of(SPAN_LOG_KEY, "true"));
    }

    // https://github.com/wavefrontHQ/wavefront-proxy/blob/3dd1fa11711a04de2d9d418e2269f0f9fb464f36/proxy/src/main/java/com/wavefront/agent/listeners/tracing/ZipkinPortUnificationHandler.java#L324-L327
    if (span.localIp() != null) {
      tags.add(Pair.of("ipv4", span.localIp())); // NOTE: this could be IPv6!!
    }

    try {
      wavefrontSender.sendSpan(name, startMillis, durationMillis, source, traceId, spanId,
          parents, followsFrom, tags, spanLogs);
    } catch (IOException | RuntimeException t) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("error sending span " + context, t);
      }
    }
  }

  @Override public void run() {
    while (!stop) {
      try {
        Pair<TraceContext, MutableSpan> contextAndSpan = spanBuffer.take();
        send(contextAndSpan._1, contextAndSpan._2);
      } catch (InterruptedException ex) {
        if (LOG.isInfoEnabled()) {
          LOG.info("reporting thread interrupted");
        }
      } catch (Throwable ex) {
        LOG.warn("Error processing buffer", ex);
      }
    }
  }

  @Override public void close() {
    stop = true;
    try {
      // wait for 5 secs max
      sendingThread.join(5000);
    } catch (InterruptedException ex) {
      // no-op
    }
  }

  static class WavefrontConsumer
      implements AnnotationConsumer<List<SpanLog>>, TagConsumer<List<Pair<String, String>>> {
    final Set<String> defaultTagKeys;
    boolean debug, hasAnnotations;

    WavefrontConsumer(Set<String> defaultTagKeys) {
      this.defaultTagKeys = defaultTagKeys;
    }

    @Override public void accept(List<SpanLog> target, long timestamp, String value) {
      hasAnnotations = true;
      target.add(new SpanLog(timestamp, Collections.singletonMap("annotation", value)));
    }

    @Override public void accept(List<Pair<String, String>> target, String key, String value) {
      if (value.isEmpty()) return;
      String lcKey = key.toLowerCase(Locale.ROOT);
      if (defaultTagKeys.contains(lcKey)) return;
      if (lcKey.equals(DEBUG_TAG_KEY)) {
        debug = true; // This tag is set out-of-band
        return;
      }
      if (lcKey.equals(ERROR_TAG_KEY)) {
        value = "true"; // Ignore the original error value
      }
      target.add(Pair.of(key, value));
    }
  }
}