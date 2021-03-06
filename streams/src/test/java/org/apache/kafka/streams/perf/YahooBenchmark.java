/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.perf;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.kstream.ForeachAction;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.Predicate;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.apache.kafka.streams.kstream.ValueMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;


/**
 * A basic DSL and data generation that emulates the behavior of the Yahoo Benchmark
 * https://yahooeng.tumblr.com/post/135321837876/benchmarking-streaming-computation-engines-at
 * Thanks to Michael Armbrust for providing the initial code for this benchmark in his blog:
 * https://databricks.com/blog/2017/06/06/simple-super-fast-streaming-engine-apache-spark.html
 */
public class YahooBenchmark {
    private final SimpleBenchmark parent;
    private final String campaignsTopic;
    private final String eventsTopic;

    static class ProjectedEvent {
        /* attributes need to be public for serializer to work */
        /* main attributes */
        public String eventType;
        public String adID;

        /* other attributes */
        public long eventTime;
        public String userID = UUID.randomUUID().toString(); // not used
        public String pageID = UUID.randomUUID().toString(); // not used
        public String addType = "banner78";  // not used
        public String ipAddress = "1.2.3.4"; // not used
    }

    static class CampaignAd {
        /* attributes need to be public for serializer to work */
        public String adID;
        public String campaignID;
    }

    public YahooBenchmark(final SimpleBenchmark parent, final String campaignsTopic, final String eventsTopic) {
        this.parent = parent;
        this.campaignsTopic = campaignsTopic;
        this.eventsTopic = eventsTopic;
    }

    // just for Yahoo benchmark
    private boolean maybeSetupPhaseCampaigns(final String topic, final String clientId,
                                             final boolean skipIfAllTests,
                                             final int numCampaigns, final int adsPerCampaign,
                                             final List<String> ads) throws Exception {
        parent.resetStats();
        // initialize topics
        if (parent.loadPhase) {
            if (skipIfAllTests) {
                // if we run all tests, the produce test will have already loaded the data
                if (parent.testName.equals(SimpleBenchmark.ALL_TESTS)) {
                    // Skipping loading phase since previously loaded
                    return true;
                }
            }
            System.out.println("Initializing topic " + topic);

            Properties props = new Properties();
            props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, parent.kafka);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

            KafkaProducer<String, String> producer = new KafkaProducer<>(props);
            for (int c = 0; c < numCampaigns; c++) {
                String campaignID = UUID.randomUUID().toString();
                for (int a = 0; a < adsPerCampaign; a++) {
                    String adId = UUID.randomUUID().toString();
                    String concat = adId + ":" + campaignID;
                    producer.send(new ProducerRecord<>(topic, adId, concat));
                    ads.add(adId);
                    parent.processedRecords.getAndIncrement();
                    parent.processedBytes += concat.length() + adId.length();
                }
            }
            return true;
        }
        return false;
    }

    // just for Yahoo benchmark
    private boolean maybeSetupPhaseEvents(final String topic, final String clientId,
                                          final boolean skipIfAllTests, final int numRecords,
                                          final List<String> ads) throws Exception {
        parent.resetStats();
        String[] eventTypes = new String[]{"view", "click", "purchase"};
        Random rand = new Random();
        // initialize topics
        if (parent.loadPhase) {
            if (skipIfAllTests) {
                // if we run all tests, the produce test will have already loaded the data
                if (parent.testName.equals(SimpleBenchmark.ALL_TESTS)) {
                    // Skipping loading phase since previously loaded
                    return true;
                }
            }
            System.out.println("Initializing topic " + topic);

            Properties props = new Properties();
            props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, parent.kafka);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);

            KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);

            long startTime = System.currentTimeMillis();

            ProjectedEvent event = new ProjectedEvent();

            Map<String, Object> serdeProps = new HashMap<>();
            final Serializer<ProjectedEvent> projectedEventSerializer = new JsonPOJOSerializer<>();
            serdeProps.put("JsonPOJOClass", ProjectedEvent.class);
            projectedEventSerializer.configure(serdeProps, false);

            for (int i = 0; i < numRecords; i++) {
                event.eventType = eventTypes[rand.nextInt(eventTypes.length - 1)];
                event.adID = ads.get(rand.nextInt(ads.size() - 1));
                event.eventTime = System.currentTimeMillis();
                byte[] value = projectedEventSerializer.serialize(topic, event);
                producer.send(new ProducerRecord<>(topic, event.adID, value));
                parent.processedRecords.getAndIncrement();
                parent.processedBytes += value.length + event.adID.length();
            }
            producer.close();

            long endTime = System.currentTimeMillis();


            parent.printResults("Producer Performance [records/latency/rec-sec/MB-sec write]: ", endTime - startTime);
            return true;
        }
        return false;
    }


    public void run() throws Exception {
        int numCampaigns = 100;
        int adsPerCampaign = 10;

        List<String> ads = new ArrayList<>(numCampaigns * adsPerCampaign);
        if (maybeSetupPhaseCampaigns(campaignsTopic, "simple-benchmark-produce-campaigns", false,
            numCampaigns, adsPerCampaign, ads)) {
            maybeSetupPhaseEvents(eventsTopic, "simple-benchmark-produce-events", false,
                parent.numRecords, ads);
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        Properties props = parent.setStreamProperties("simple-benchmark-yahoo" + new Random().nextInt());

        final KafkaStreams streams = createYahooBenchmarkStreams(props, campaignsTopic, eventsTopic, latch, parent.numRecords);
        parent.runGenericBenchmark(streams, "Streams Yahoo Performance [records/latency/rec-sec/MB-sec counted]: ", latch);

    }
    // Note: these are also in the streams example package, eventually use 1 file
    private class JsonPOJOSerializer<T> implements Serializer<T> {
        private final ObjectMapper objectMapper = new ObjectMapper();

        /**
         * Default constructor needed by Kafka
         */
        public JsonPOJOSerializer() {
        }

        @Override
        public void configure(Map<String, ?> props, boolean isKey) {
        }

        @Override
        public byte[] serialize(String topic, T data) {
            if (data == null)
                return null;

            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new SerializationException("Error serializing JSON message", e);
            }
        }

        @Override
        public void close() {
        }

    }

    // Note: these are also in the streams example package, eventuall use 1 file
    private class JsonPOJODeserializer<T> implements Deserializer<T> {
        private ObjectMapper objectMapper = new ObjectMapper();

        private Class<T> tClass;

        /**
         * Default constructor needed by Kafka
         */
        public JsonPOJODeserializer() {
        }

        @SuppressWarnings("unchecked")
        @Override
        public void configure(Map<String, ?> props, boolean isKey) {
            tClass = (Class<T>) props.get("JsonPOJOClass");
        }

        @Override
        public T deserialize(String topic, byte[] bytes) {
            if (bytes == null)
                return null;

            T data;
            try {
                data = objectMapper.readValue(bytes, tClass);
            } catch (Exception e) {
                throw new SerializationException(e);
            }

            return data;
        }

        @Override
        public void close() {

        }
    }


    private KafkaStreams createYahooBenchmarkStreams(final Properties streamConfig, final String campaignsTopic, final String eventsTopic,
                                                     final CountDownLatch latch, final int numRecords) {
        Map<String, Object> serdeProps = new HashMap<>();
        final Serializer<ProjectedEvent> projectedEventSerializer = new JsonPOJOSerializer<>();
        serdeProps.put("JsonPOJOClass", ProjectedEvent.class);
        projectedEventSerializer.configure(serdeProps, false);
        final Deserializer<ProjectedEvent> projectedEventDeserializer = new JsonPOJODeserializer<>();
        serdeProps.put("JsonPOJOClass", ProjectedEvent.class);
        projectedEventDeserializer.configure(serdeProps, false);

        final KStreamBuilder builder = new KStreamBuilder();
        final KStream<String, ProjectedEvent> kEvents = builder.stream(Serdes.String(),
            Serdes.serdeFrom(projectedEventSerializer, projectedEventDeserializer), eventsTopic);
        final KTable<String, String> kCampaigns = builder.table(Serdes.String(), Serdes.String(),
            campaignsTopic, "campaign-state");


        KStream<String, ProjectedEvent> filteredEvents = kEvents
            // use peek to quick when last element is processed
            .peek(new ForeachAction<String, ProjectedEvent>() {
                @Override
                public void apply(String key, ProjectedEvent value) {
                    parent.processedRecords.getAndIncrement();
                    if (parent.processedRecords.get() % 1000000 == 0) {
                        System.out.println("Processed " + parent.processedRecords.get());
                    }
                    if (parent.processedRecords.get() >= numRecords) {
                        latch.countDown();
                    }
                }
            })
            // only keep "view" events
            .filter(new Predicate<String, ProjectedEvent>() {
                @Override
                public boolean test(final String key, final ProjectedEvent value) {
                    return value.eventType.equals("view");
                }
            })
            // select just a few of the columns
            .mapValues(new ValueMapper<ProjectedEvent, ProjectedEvent>() {
                @Override
                public ProjectedEvent apply(ProjectedEvent value) {
                    ProjectedEvent event = new ProjectedEvent();
                    event.adID = value.adID;
                    event.eventTime = value.eventTime;
                    event.eventType = value.eventType;
                    return event;
                }
            });

        // deserialize the add ID and campaign ID from the stored value in Kafka
        KTable<String, CampaignAd> deserCampaigns = kCampaigns.mapValues(new ValueMapper<String, CampaignAd>() {
            @Override
            public CampaignAd apply(String value) {
                String[] parts = value.split(":");
                CampaignAd cAdd = new CampaignAd();
                cAdd.adID = parts[0];
                cAdd.campaignID = parts[1];
                return cAdd;
            }
        });

        // join the events with the campaigns
        KStream<String, String> joined = filteredEvents.join(deserCampaigns,
            new ValueJoiner<ProjectedEvent, CampaignAd, String>() {
                @Override
                public String apply(ProjectedEvent value1, CampaignAd value2) {
                    return value2.campaignID;
                }
            }, Serdes.String(), Serdes.serdeFrom(projectedEventSerializer, projectedEventDeserializer));


        // key by campaign rather than by ad as original
        KStream<String, String> keyedByCampaign = joined
            .selectKey(new KeyValueMapper<String, String, String>() {
                @Override
                public String apply(String key, String value) {
                    return value;
                }
            });

        // calculate windowed counts
        keyedByCampaign
            .groupByKey(Serdes.String(), Serdes.String())
            .count(TimeWindows.of(10 * 1000), "time-windows");

        return new KafkaStreams(builder, streamConfig);
    }
}
