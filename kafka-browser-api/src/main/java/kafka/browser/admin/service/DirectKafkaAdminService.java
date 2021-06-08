package kafka.browser.admin.service;

import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PreDestroy;

import kafka.browser.admin.KafkaMessageGetter;
import kafka.browser.admin.KafkaMessageGetter.SearchDetails;
import kafka.browser.admin.KafkaMessageSender;
import kafka.browser.admin.KafkaTopicOffsetFinder;
import kafka.browser.admin.adapter.DirectKafkaAdminAdapter;
import kafka.browser.admin.service.MessageQuery.KeyMessageQueryValue;
import kafka.browser.http.consumergroup.ConsumerGroupDto;
import kafka.browser.http.consumergroup.ConsumerGroupDto.PartitionInfo;
import kafka.browser.http.consumergroup.ConsumerGroupMetaDataDto;
import kafka.browser.http.topic.TopicDto;

import static kafka.browser.http.consumergroup.ConsumerGroupDto.Partition;

public class DirectKafkaAdminService implements KafkaAdminService {

    private final KafkaTopicOffsetFinder kafkaTopicOffsetFinder;
    private final KafkaMessageGetter kafkaMessageGetter;
    private final DirectKafkaAdminAdapter kafkaAdminAdapter;
    private final KafkaMessageSender kafkaMessageSender;
    private final Boolean allowModification;
    private final ExecutorService searchMessageExecutor = Executors.newFixedThreadPool(16);
    private final static Logger LOGGER = LoggerFactory.getLogger(DirectKafkaAdminAdapter.class);

    public DirectKafkaAdminService(KafkaTopicOffsetFinder kafkaTopicOffsetFinder,
                                   KafkaMessageGetter kafkaMessageGetter,
                                   DirectKafkaAdminAdapter kafkaAdminAdapter,
                                   KafkaMessageSender kafkaMessageSender,
                                   Boolean allowModification) {
        this.kafkaTopicOffsetFinder = kafkaTopicOffsetFinder;
        this.kafkaMessageGetter = kafkaMessageGetter;
        this.kafkaAdminAdapter = kafkaAdminAdapter;
        this.kafkaMessageSender = kafkaMessageSender;
        this.allowModification = allowModification;
    }


    @Override
    public List<String> getConsumerGroupsNames() {
        return kafkaAdminAdapter.getConsumerGroupListing().stream()
                .map(ConsumerGroupListing::groupId)
                .collect(Collectors.toList());
    }

    @Override
    public ConsumerGroupDto getConsumerGroup(String name) {
        var partitionData = kafkaAdminAdapter.getConsumerGroupOffsets(name);

        List<TopicPartition> topicPartitions = new ArrayList<>(partitionData.keySet());
        topicPartitions.sort(Comparator.comparing(TopicPartition::toString));
        var lastOffsets = kafkaTopicOffsetFinder.findLastOffsets(topicPartitions);
        var topicToOffsetMap = lastOffsets.stream().collect(Collectors.groupingBy(it -> it.topicName));
        var partitionsInfo = partitionData.entrySet()
                .stream()
                .map(entry -> {
                    var topic = entry.getKey().topic();
                    var partitionNumber = entry.getKey().partition();
                    var offset = entry.getValue().offset();
                    Long aLong = getPartitionEndOffset(topicToOffsetMap, topic, partitionNumber);
                    return new PartitionInfo(new Partition(topic + "-" + partitionNumber, partitionNumber,
                            topic, offset), aLong - offset);
                })
                .collect(Collectors.toList());

        Set<String> topics = partitionData.keySet().stream()
                .map(TopicPartition::topic)
                .collect(Collectors.toSet());
        return new ConsumerGroupDto(name, topics, partitionsInfo);
    }

    private Long getPartitionEndOffset(Map<String, List<KafkaTopicOffsetFinder.OffsetInfoWithTopic>> topicToOffsetMap, String topic, int partitionNumber) {
        KafkaTopicOffsetFinder.OffsetInfoWithTopic offsetInfoWithTopic = topicToOffsetMap.get(topic).get(0);
        if (partitionNumber >= offsetInfoWithTopic.offsets.size()) {
            LOGGER.warn("Partition number greater then offset, partition: {}, offsets: {}", partitionNumber, offsetInfoWithTopic);
            return 0L;
        }
        return offsetInfoWithTopic.offsets.get(partitionNumber);
    }

    @Override
    public ConsumerGroupMetaDataDto getConsumerGroupMetaData(String name) {
        return new ConsumerGroupMetaDataDto(kafkaAdminAdapter.describeConsumerGroup(name));
    }

    @Override
    public List<ConsumerGroupDto> getAllConsumerGroups() {
        var consumerGroups = new ArrayList<ConsumerGroupDto>();
        for (String consumerGroupsName : getConsumerGroupsNames()) {
            var partitionData = kafkaAdminAdapter.getConsumerGroupOffsets(consumerGroupsName);

            Set<String> topics = partitionData.keySet().stream()
                    .map(TopicPartition::topic)
                    .collect(Collectors.toSet());
            consumerGroups.add(new ConsumerGroupDto(consumerGroupsName, topics, null));
        }

        return consumerGroups;
    }

    @Override
    public List<String> getConsumerGroupsForGivenTopic(String topicName) {
        var consumerGroup = getConsumerGroupsNames();

        List<String> consumerInTopic = new ArrayList<>();

        for (var consumer : consumerGroup) {
            var isInTopic = kafkaAdminAdapter.getConsumerGroupOffsets(consumer)
                    .keySet()
                    .stream()
                    .anyMatch(it -> it.topic().equals(topicName));
            if (isInTopic) {
                consumerInTopic.add(consumer);
            }
        }
        return consumerInTopic;
    }

    @Override
    public TopicDto getTopic(String topicName) {
        var topicDescription = kafkaAdminAdapter.describeTopic(topicName);
        String name = topicDescription.name();
        List<Integer> partitions = topicDescription.partitions()
                .stream()
                .map(TopicPartitionInfo::partition)
                .collect(Collectors.toList());
        KafkaTopicOffsetFinder.OffsetInfo lastOffset = kafkaTopicOffsetFinder.findLastOffsets(name, partitions);
        var partitionsInfo = topicDescription.partitions().stream()
                .map(it -> new TopicDto.PartitionInfo(name + "-" + it.partition(), it.partition(), lastOffset.offsets.get(it.partition())))
                .collect(Collectors.toList());
        return new TopicDto(name, lastOffset.offsetSum, partitionsInfo.size(), partitionsInfo);
    }

    @Override
    public List<TopicDto> getTopics() {
        try {
            Set<String> topics = kafkaAdminAdapter.getTopicNames();
            List<TopicPartition> topicPartitionStream = kafkaAdminAdapter.describeTopics(topics)
                    .entrySet()
                    .stream()
                    .flatMap(it -> it.getValue().partitions()
                            .stream()
                            .map(pInfo -> new TopicPartition(it.getKey(), pInfo.partition())))
                    .collect(Collectors.toList());
            var offsets = kafkaTopicOffsetFinder.findLastOffsets(topicPartitionStream);
            return offsets.stream()
                    .map(it -> {
                        int[] count = {0};
                        return new TopicDto(it.topicName, it.offsetSum, it.offsets.size(), it.offsets.stream()
                                .map(offset -> new TopicDto.PartitionInfo(it.topicName + "-" + count[0], count[0]++, offset))
                                .collect(Collectors.toList()));
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> getTopicNames() {
        return new ArrayList<>(kafkaAdminAdapter.getTopicNames());
    }

    @Override
    public void sendMessage(String topic, String key, String message) {
        if (!allowModification)
            throw new ActionNotAllowed("cannot send message to this kafka cluster, action not allowed");
        kafkaMessageSender.sendMessage(topic, key, message);
    }

    @Override
    public void deleteConsumerGroup(String consumerGroupName) {
        if (!allowModification)
            throw new ActionNotAllowed(String.format("consumer group %s deletion", consumerGroupName));
        kafkaAdminAdapter.deleteConsumerGroup(consumerGroupName);
    }

    public void deleteTopic(String topicName) {
        if (!allowModification) throw new ActionNotAllowed(String.format("topic %s deletion", topicName));
        kafkaAdminAdapter.deleteTopic(topicName);

    }

    @Override
    public String getLastMessage(TopicPartition topicPartition) {
        return kafkaMessageGetter.getLastMessage(topicPartition);
    }

    @Override
    public Optional<String> getMessage(TopicPartition topicPartition, long offset) {
        return kafkaMessageGetter.getMessage(topicPartition, offset);
    }

    @Override
    public List<ConsumerRecord<byte[], byte[]>> findMessage(String topic, MessageQuery messageQuery, Instant from, Instant to) {
        long time = System.currentTimeMillis();
        Function<ConsumerRecord<byte[], byte[]>, Boolean> messagePicker = getMessagePicker(messageQuery);
        var fromOffsets = kafkaTopicOffsetFinder.findOffsetByTime(topic, from.toEpochMilli());
        var toOffsets = kafkaTopicOffsetFinder.findOffsetByTime(topic, to.toEpochMilli());
        Map<TopicPartition, Long> endOffsets = new HashMap<>();
        if (toOffsets.containsValue(null)) {
            var offsets = kafkaTopicOffsetFinder.findLastOffsets(toOffsets.keySet()).get(0).offsets;
            for (int i = 0; i < offsets.size(); i++) {
                endOffsets.put(new TopicPartition(topic, i), offsets.get(i));
            }
        }
        Set<TopicPartition> topicPartitions = fromOffsets.keySet();
        var partitionToSearchDetails = new HashMap<TopicPartition, SearchDetails>();
        topicPartitions.forEach(it -> {
            if (fromOffsets.get(it) != null) {
                var toOffset = toOffsets.get(it) == null ? endOffsets.get(it) : toOffsets.get(it).offset();
                partitionToSearchDetails.put(it, new SearchDetails(fromOffsets.get(it).offset(), toOffset));
            }
        });
        List<ConsumerRecord<byte[], byte[]>> collect = partitionToSearchDetails.isEmpty() ?
                Collections.emptyList() : kafkaMessageGetter.getMessages(messagePicker, partitionToSearchDetails);
        System.out.println("Find message time " + ((System.currentTimeMillis() - time) / 1000));
        return collect;
    }

    private Function<ConsumerRecord<byte[], byte[]>, Boolean> getMessagePicker(MessageQuery messageQuery) {
        switch (messageQuery.queryType) {
            case Message:
                return it -> new String(it.value()).contains((String) messageQuery.value);
            case Key:
                return it -> new String(it.key()).equals(messageQuery.value);
            case KeyAndMessage:
                return it -> {
                    KeyMessageQueryValue value = (KeyMessageQueryValue) messageQuery.value;
                    return new String(it.key()).equals(value.key) && new String(it.value()).contains(value.message);
                };
            default:
                throw new RuntimeException("unsupported message query");
        }
    }

    @Override
    public void close() {
        searchMessageExecutor.shutdownNow();
        kafkaAdminAdapter.close();
    }
}
