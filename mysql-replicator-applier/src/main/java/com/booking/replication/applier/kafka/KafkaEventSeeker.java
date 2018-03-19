package com.booking.replication.applier.kafka;

import com.booking.replication.applier.EventSeeker;
import com.booking.replication.model.*;
import com.booking.replication.model.augmented.AugmentedEventHeader;
import com.booking.replication.model.augmented.AugmentedEventHeaderImplementation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;

public class KafkaEventSeeker implements EventSeeker, Comparator<Checkpoint> {
    public interface Configuration extends KafkaEventApplier.Configuration {
        String GROUP_ID = "kafka.group.id";
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Checkpoint checkpoint;
    private boolean seeked;

    public KafkaEventSeeker(Checkpoint checkpoint) {
        this.checkpoint = checkpoint;
        this.seeked = false;
    }

    public KafkaEventSeeker(Map<String, String> configuration, Checkpoint checkpoint) {
        String bootstrapServers = configuration.get(Configuration.BOOTSTRAP_SERVERS);
        String groupId = configuration.get(Configuration.GROUP_ID);
        String topic = configuration.get(Configuration.TOPIC);

        Objects.requireNonNull(bootstrapServers, String.format("Configuration required: %s", Configuration.BOOTSTRAP_SERVERS));
        Objects.requireNonNull(groupId, String.format("Configuration required: %s", Configuration.GROUP_ID));
        Objects.requireNonNull(topic, String.format("Configuration required: %s", Configuration.TOPIC));

        this.checkpoint = this.geCheckpoint(checkpoint, bootstrapServers, groupId, topic);
        this.seeked = false;
    }

    private Checkpoint geCheckpoint(Checkpoint checkpoint, String bootstrapServers, String groupId, String topic) {
        Map<String, Object> configuration = new HashMap<>();

        configuration.put("bootstrap.servers", bootstrapServers);
        configuration.put("group.id", groupId);
        configuration.put("key.deserializer", ByteArrayDeserializer.class.getName());
        configuration.put("value.deserializer", ByteArrayDeserializer.class.getName());

        try (Consumer<byte[], byte[]> consumer = new KafkaConsumer<>(configuration)) {
            Checkpoint lastCheckpoint = checkpoint;

            List<TopicPartition> topicPartitions = consumer.partitionsFor(topic).stream().map(partitionInfo -> new TopicPartition(partitionInfo.topic(), partitionInfo.partition())).collect(Collectors.toList());

            for (TopicPartition topicPartition : topicPartitions) {
                consumer.assign(Collections.singletonList(topicPartition));
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(Collections.singletonList(topicPartition));
                consumer.seek(topicPartition, endOffsets.get(topicPartition) - 1);

                ConsumerRecords<byte[], byte[]> consumerRecords = consumer.poll(100);

                for (ConsumerRecord<byte[], byte[]> consumerRecord : consumerRecords) {
                    Checkpoint currentCheckpoint = KafkaEventSeeker.MAPPER.readValue(
                            consumerRecord.key(), AugmentedEventHeaderImplementation.class
                    ).getCheckpoint();

                    if (this.compare(lastCheckpoint, currentCheckpoint) < 0) {
                        lastCheckpoint = currentCheckpoint;
                    }
                }
            }

            return lastCheckpoint;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public Event apply(Event event) {
        if (this.seeked) {
            return event;
        } else if (this.compare(this.checkpoint, AugmentedEventHeader.class.cast(event.getHeader()).getCheckpoint()) < 0) {
            this.seeked = true;
            return event;
        } else {
            return null;
        }
    }

    @Override
    public int compare(Checkpoint checkpoint1, Checkpoint checkpoint2) {
        if (checkpoint1 != null && checkpoint1.getPseudoGTID() != null &&
            checkpoint2 != null && checkpoint2.getPseudoGTID() != null) {
            if (checkpoint1.getPseudoGTID().equals(checkpoint2.getPseudoGTID())) {
                return Integer.compare(checkpoint1.getPseudoGTIDIndex(), checkpoint2.getPseudoGTIDIndex());
            } else {
                return checkpoint1.getPseudoGTID().compareTo(checkpoint2.getPseudoGTID());
            }
        } else if (checkpoint1 != null && checkpoint1.getPseudoGTID() != null) {
            return Integer.MAX_VALUE;
        } else if (checkpoint2 != null && checkpoint2.getPseudoGTID() != null) {
            return Integer.MIN_VALUE;
        } else if (checkpoint1 != null && checkpoint2 != null) {
            if (checkpoint1.getBinlogFilename() != null && checkpoint2.getBinlogFilename() != null) {
                if (checkpoint1.getBinlogFilename().equals(checkpoint2.getBinlogFilename())) {
                    return Long.compare(checkpoint1.getBinlogPosition(), checkpoint2.getBinlogPosition());
                } else {
                    return checkpoint1.getBinlogFilename().compareTo(checkpoint2.getBinlogFilename());
                }
            } else if (checkpoint1.getBinlogFilename() != null) {
                return Integer.MAX_VALUE;
            } else if (checkpoint2.getBinlogFilename() != null) {
                return Integer.MIN_VALUE;
            } else {
                return 0;
            }
        } else if (checkpoint1 != null) {
            return Integer.MAX_VALUE;
        } else if (checkpoint2 != null) {
            return Integer.MIN_VALUE;
        } else {
            return 0;
        }
    }
}