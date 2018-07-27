/*
 * Copyright 2014 Dominic LoBue Copyright 2014 Brandon Seibel Licensed under the
 * Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.codahale.metrics.riemann;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Clock;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metered;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;

import io.riemann.riemann.client.EventDSL;

/**
 * A reporter that submits events to Riemann
 */
public class RiemannReporter
    extends
    ScheduledReporter {

    /**
     * Returns a new {@link Builder} for {@link RiemannReporter}.
     *
     * @param registry the registry to report
     * @return a {@link Builder} instance for a {@link RiemannReporter}
     */
    public static Builder forRegistry(MetricRegistry registry) {
        return new Builder(registry);
    }

    /**
     * A builder for {@link RiemannReporter} instances. Defaults to not using a
     * prefix, using the default clock, converting rates to events/second,
     * converting durations to milliseconds, and not filtering metrics.
     */
    public static class Builder {

        private final MetricRegistry registry;

        private Clock clock;

        private TimeUnit rateUnit;

        private TimeUnit durationUnit;

        private MetricFilter filter;

        private Float ttl;

        private String prefix;

        private String separator;

        private String localHost;

        private final List<String> tags;

        private ValueFilterMap valueFilterMap = new ValueFilterMap();

        private Builder(MetricRegistry registry) {
            this.registry = registry;
            this.clock = Clock.defaultClock();
            this.rateUnit = TimeUnit.SECONDS;
            this.durationUnit = TimeUnit.MILLISECONDS;
            this.filter = MetricFilter.ALL;
            this.ttl = null;
            this.tags = new ArrayList<String>();
            this.prefix = null;
            this.separator = " ";
            try {
                this.localHost = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                this.localHost = null;
            }
        }

        /**
         * Use the given {@link Clock} instance for the time.
         *
         * @param clock a {@link Clock} instance
         * @return {@code this}
         */
        public Builder withClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Prefix all metric names with the given string.
         *
         * @param prefix the prefix for all metric names
         * @return {@code this}
         */
        public Builder prefixedWith(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /**
         * Convert rates to the given time unit.
         *
         * @param rateUnit a unit of time
         * @return {@code this}
         */
        public Builder convertRatesTo(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        /**
         * Convert durations to the given time unit.
         *
         * @param durationUnit a unit of time
         * @return {@code this}
         */
        public Builder convertDurationsTo(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        /**
         * Only report metrics which match the given filter.
         *
         * @param filter a {@link MetricFilter}
         * @return {@code this}
         */
        public Builder filter(MetricFilter filter) {
            this.filter = filter;
            return this;
        }

        /**
         * Default time to live of Riemann event.
         *
         * @param ttl a {@link Float}
         * @return {@code this}
         */
        public Builder withTtl(Float ttl) {
            this.ttl = ttl;
            return this;
        }

        public Builder withValueFilterMap(ValueFilterMap valueFilterMap) {
            this.valueFilterMap = valueFilterMap;
            return this;
        }

        /**
         * Separator between metric name components
         *
         * @param separator a {@link String}
         * @return {@code this}
         */
        public Builder useSeparator(String separator) {
            this.separator = separator;
            return this;
        }

        /**
         * Event source host
         *
         * @param localHost a {@link String}
         * @return {@code this}
         */
        public Builder localHost(String localHost) {
            this.localHost = localHost;
            return this;
        }

        /**
         * Tags to attach to events.
         *
         * @param tags a {@code Collection<String>}
         * @return {@code this}
         */
        public Builder tags(Collection<String> tags) {
            this.tags.clear();
            this.tags.addAll(tags);
            return this;
        }

        /**
         * Builds a {@link RiemannReporter} with the given properties, sending
         * metrics using the given {@link Riemann} client.
         *
         * @param riemann a {@link Riemann} client.
         * @return a {@link RiemannReporter}
         */
        public RiemannReporter build(Riemann riemann) {
            return new RiemannReporter(registry, riemann, clock, rateUnit,
                                       durationUnit, ttl, prefix, separator,
                                       localHost, tags, filter, valueFilterMap);
        }
    }

    private static final Logger log = LoggerFactory
        .getLogger(RiemannReporter.class);

    private final Riemann riemann;

    private final Clock clock;

    private final String prefix;

    private final String separator;

    private final String localHost;

    private final List<String> tags;

    private final Float ttl;

    private final ValueFilterMap valueFilterMap;

    private RiemannReporter(MetricRegistry registry, Riemann riemann,
                            Clock clock, TimeUnit rateUnit,
                            TimeUnit durationUnit, Float ttl, String prefix,
                            String separator, String localHost,
                            List<String> tags, MetricFilter filter,
                            ValueFilterMap valueFilterMap) {
        super(registry, "riemann-reporter", filter, rateUnit, durationUnit);
        this.riemann = riemann;
        this.clock = clock;
        this.prefix = prefix;
        this.separator = separator;
        this.localHost = localHost;
        this.tags = tags;
        this.ttl = ttl;
        this.valueFilterMap = valueFilterMap;
    }

    private interface EventClosure {

        public EventDSL name(String... components);
    }

    private EventClosure newEvent(final String metricName, final long timestamp,
                                  final String metricType) {
        final String prefix = this.prefix;
        final String separator = this.separator;
        return new EventClosure() {

            public EventDSL name(String... components) {
                EventDSL event = riemann.client.event();
                if (localHost != null) {
                    event.host(localHost);
                }
                if (ttl != null) {
                    event.ttl(ttl);
                }
                if (!tags.isEmpty()) {
                    event.tags(tags);
                }
                final StringBuilder sb = new StringBuilder();
                if (prefix != null) {
                    sb.append(prefix);
                    sb.append(separator);
                }
                sb.append(metricName);

                for (String part : components) {
                    sb.append(separator);
                    sb.append(part);
                }

                event.service(sb.toString());
                event.time(timestamp);
                event.attribute("metric-type", metricType);
                return event;
            }
        };
    }

    @Override
    public void report(SortedMap<String, Gauge> gauges,
                       SortedMap<String, Counter> counters,
                       SortedMap<String, Histogram> histograms,
                       SortedMap<String, Meter> meters,
                       SortedMap<String, Timer> timers) {
        final long timestamp = clock.getTime() / 1000;

        log.debug("Reporting metrics: for {} at {}", timestamp,
                  System.currentTimeMillis());
        // oh it'd be lovely to use Java 7 here
        try {
            riemann.connect();

            for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
                reportGauge(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                reportCounter(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
                reportHistogram(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Meter> entry : meters.entrySet()) {
                reportMetered(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Timer> entry : timers.entrySet()) {
                reportTimer(entry.getKey(), entry.getValue(), timestamp);
            }

            log.trace("Flushing events to riemann");
            riemann.client.flush();
            log.debug("Completed at {}", System.currentTimeMillis());
        } catch (IOException e) {
            log.warn("Unable to report to Riemann", riemann, e);
        }
    }

    @Override
    public void stop() {
        try {
            super.stop();
        } finally {
            try {
                riemann.close();
            } catch (IOException e) {
                log.debug("Error disconnecting from Riemann", riemann, e);
            }
        }
    }

    private void reportTimer(String name, Timer timer, long timestamp) {
        final Snapshot snapshot = timer.getSnapshot();
        final EventClosure reporter = newEvent(name, timestamp, timer.getClass()
            .getSimpleName());

        reporter.name("max").metric(convertDuration(snapshot.getMax()))
            .state(valueFilterMap.state(timer, "max")).send();
        reporter.name("mean").metric(convertDuration(snapshot.getMean()))
            .state(valueFilterMap.state(timer, "mean")).send();
        reporter.name("min").metric(convertDuration(snapshot.getMin()))
            .state(valueFilterMap.state(timer, "min")).send();
        reporter.name("stddev").metric(convertDuration(snapshot.getStdDev()))
            .state(valueFilterMap.state(timer, "stddev")).send();
        reporter.name("p50").metric(convertDuration(snapshot.getMedian()))
            .state(valueFilterMap.state(timer, "p50")).send();
        reporter.name("p75")
            .metric(convertDuration(snapshot.get75thPercentile()))
            .state(valueFilterMap.state(timer, "p75")).send();
        reporter.name("p95")
            .metric(convertDuration(snapshot.get95thPercentile()))
            .state(valueFilterMap.state(timer, "p95")).send();
        reporter.name("p98")
            .metric(convertDuration(snapshot.get98thPercentile()))
            .state(valueFilterMap.state(timer, "p98")).send();
        reporter.name("p99")
            .metric(convertDuration(snapshot.get99thPercentile()))
            .state(valueFilterMap.state(timer, "p99")).send();
        reporter.name("p999")
            .metric(convertDuration(snapshot.get999thPercentile()))
            .state(valueFilterMap.state(timer, "p999")).send();

        reportMetered(name, timer, timestamp);
    }

    private void reportMetered(String name, Metered meter, long timestamp) {
        final EventClosure reporter = newEvent(name, timestamp, meter.getClass()
            .getSimpleName());
        reporter.name("count").metric(meter.getCount())
            .state(valueFilterMap.state(meter, "count")).send();
        reporter.name("m1_rate").metric(convertRate(meter.getOneMinuteRate()))
            .state(valueFilterMap.state(meter, "m1_rate")).send();
        reporter.name("m5_rate").metric(convertRate(meter.getFiveMinuteRate()))
            .state(valueFilterMap.state(meter, "m5_rate")).send();
        reporter.name("m15_rate")
            .metric(convertRate(meter.getFifteenMinuteRate()))
            .state(valueFilterMap.state(meter, "m15_rate")).send();
        reporter.name("mean_rate").metric(convertRate(meter.getMeanRate()))
            .state(valueFilterMap.state(meter, "mean_rate")).send();
    }

    private void reportHistogram(String name, Histogram histogram,
                                 long timestamp) {
        final Snapshot snapshot = histogram.getSnapshot();
        final EventClosure reporter = newEvent(name, timestamp, histogram
            .getClass().getSimpleName());

        reporter.name("count").metric(histogram.getCount())
            .state(valueFilterMap.state(histogram, "count")).send();
        reporter.name("max").metric(snapshot.getMax())
            .state(valueFilterMap.state(histogram, "max")).send();
        reporter.name("mean").metric(snapshot.getMean())
            .state(valueFilterMap.state(histogram, "mean")).send();
        reporter.name("min").metric(snapshot.getMin())
            .state(valueFilterMap.state(histogram, "min")).send();
        reporter.name("stddev").metric(snapshot.getStdDev())
            .state(valueFilterMap.state(histogram, "stddev")).send();
        reporter.name("p50").metric(snapshot.getMedian())
            .state(valueFilterMap.state(histogram, "p50")).send();
        reporter.name("p75").metric(snapshot.get75thPercentile())
            .state(valueFilterMap.state(histogram, "p75")).send();
        reporter.name("p95").metric(snapshot.get95thPercentile())
            .state(valueFilterMap.state(histogram, "max")).send();
        reporter.name("p98").metric(snapshot.get98thPercentile())
            .state(valueFilterMap.state(histogram, "p95")).send();
        reporter.name("p99").metric(snapshot.get99thPercentile())
            .state(valueFilterMap.state(histogram, "p99")).send();
        reporter.name("p999").metric(snapshot.get999thPercentile())
            .state(valueFilterMap.state(histogram, "p999")).send();
    }

    private void reportCounter(String name, Counter counter, long timestamp) {
        final EventClosure reporter = newEvent(name, timestamp, counter
            .getClass().getSimpleName());
        reporter.name("count").metric(counter.getCount())
            .state(valueFilterMap.state(counter)).send();
    }

    private void reportGauge(String name, Gauge gauge, long timestamp) {
        final EventClosure reporter = newEvent(name, timestamp, gauge.getClass()
            .getSimpleName());
        Object o = gauge.getValue();

        if (o instanceof Float) {
            reporter.name().metric((Float) o).send();
        } else if (o instanceof Double) {
            reporter.name().metric((Double) o).send();
        } else if (o instanceof Byte) {
            reporter.name().metric((Byte) o).send();
        } else if (o instanceof Short) {
            reporter.name().metric((Short) o).send();
        } else if (o instanceof Integer) {
            reporter.name().metric((Integer) o).send();
        } else if (o instanceof Long) {
            reporter.name().metric((Long) o).send();
        } else if (o == null) {
            log.debug("Gauge {} has a null value", name);
        } else {
            log.debug("Gauge {} was of an unknown type: {} ", name,
                      o.getClass().toString());
        }
    }
}
