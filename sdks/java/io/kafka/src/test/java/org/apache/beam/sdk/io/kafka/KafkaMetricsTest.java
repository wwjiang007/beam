/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.kafka;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.beam.runners.core.metrics.GaugeCell;
import org.apache.beam.runners.core.metrics.HistogramCell;
import org.apache.beam.runners.core.metrics.MetricsContainerImpl;
import org.apache.beam.sdk.metrics.MetricName;
import org.apache.beam.sdk.metrics.MetricsEnvironment;
import org.apache.beam.sdk.util.HistogramData;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Lists;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link KafkaSinkMetrics}. */
// TODO:Naireen - Refactor to remove duplicate code between the two sinks
@RunWith(JUnit4.class)
public class KafkaMetricsTest {
  public static class TestHistogramCell extends HistogramCell {
    public List<Double> values = Lists.newArrayList();
    private MetricName metricName = MetricName.named("KafkaSink", "name");

    public TestHistogramCell(KV<MetricName, HistogramData.BucketType> kv) {
      super(kv);
    }

    @Override
    public void update(double value) {
      values.add(value);
    }

    @Override
    public MetricName getName() {
      return metricName;
    }
  }

  public static class TestMetricsContainer extends MetricsContainerImpl {
    public ConcurrentHashMap<KV<MetricName, HistogramData.BucketType>, TestHistogramCell>
        histograms =
            new ConcurrentHashMap<KV<MetricName, HistogramData.BucketType>, TestHistogramCell>();

    public ConcurrentHashMap<MetricName, GaugeCell> gauges =
        new ConcurrentHashMap<MetricName, GaugeCell>();

    public TestMetricsContainer() {
      super("TestStep");
    }

    @Override
    public TestHistogramCell getHistogram(
        MetricName metricName, HistogramData.BucketType bucketType) {
      histograms.computeIfAbsent(KV.of(metricName, bucketType), kv -> new TestHistogramCell(kv));
      return histograms.get(KV.of(metricName, bucketType));
    }

    @Override
    public GaugeCell getGauge(MetricName metricName) {
      gauges.computeIfAbsent(metricName, name -> new GaugeCell(metricName));
      return gauges.get(metricName);
    }

    @Override
    public void reset() {
      histograms.clear();
      gauges.clear();
    }
  }

  @Test
  public void testNoOpKafkaMetrics() throws Exception {
    TestMetricsContainer testContainer = new TestMetricsContainer();
    MetricsEnvironment.setCurrentContainer(testContainer);

    KafkaMetrics results = KafkaMetrics.NoOpKafkaMetrics.getInstance();
    results.updateSuccessfulRpcMetrics("test-topic", Duration.ofMillis(10));
    results.updateBacklogBytes("test-topic", 0, 10);
    results.flushBufferedMetrics();

    assertThat(testContainer.histograms.size(), equalTo(0));
    assertThat(testContainer.gauges.size(), equalTo(0));
  }

  @Test
  public void testKafkaRPCLatencyMetrics() throws Exception {
    TestMetricsContainer testContainer = new TestMetricsContainer();
    MetricsEnvironment.setCurrentContainer(testContainer);

    KafkaSinkMetrics.setSupportKafkaMetrics(true);

    KafkaMetrics results = KafkaSinkMetrics.kafkaMetrics();

    results.updateSuccessfulRpcMetrics("test-topic", Duration.ofMillis(10));
    results.updateBacklogBytes("test-topic", 0, 10);

    results.flushBufferedMetrics();
    // RpcLatency*rpc_method:POLL;topic_name:test-topic
    MetricName histogramName =
        MetricName.named(
            "KafkaSink",
            "RpcLatency*rpc_method:POLL;topic_name:test-topic;",
            ImmutableMap.of("PER_WORKER_METRIC", "true"));
    HistogramData.BucketType bucketType = HistogramData.ExponentialBuckets.of(1, 17);

    assertThat(testContainer.histograms.size(), equalTo(1));
    assertThat(
        testContainer.histograms.get(KV.of(histogramName, bucketType)).values,
        containsInAnyOrder(Double.valueOf(10.0)));

    MetricName gaugeName =
        MetricName.named(
            "KafkaSink",
            "EstimatedBacklogSize*partition_id:0;topic_name:test-topic;",
            ImmutableMap.of("PER_WORKER_METRIC", "true"));
    assertThat(testContainer.gauges.size(), equalTo(1));
    assertThat(testContainer.gauges.get(gaugeName).getCumulative().value(), equalTo(10L));
  }

  @Test
  public void testKafkaRPCLatencyMetricsAreNotRecorded() throws Exception {
    TestMetricsContainer testContainer = new TestMetricsContainer();
    MetricsEnvironment.setCurrentContainer(testContainer);

    KafkaSinkMetrics.setSupportKafkaMetrics(false);

    KafkaMetrics results = KafkaSinkMetrics.kafkaMetrics();

    results.updateSuccessfulRpcMetrics("test-topic", Duration.ofMillis(10));

    results.flushBufferedMetrics();
    assertThat(testContainer.histograms.size(), equalTo(0));
  }
}
