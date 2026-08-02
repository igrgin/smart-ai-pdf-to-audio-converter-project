package dev.audiobook.platform.worktransport.internal;

import dev.audiobook.platform.worktransport.WorkTransport;

import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.PubsubMessage;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class GooglePubSubWorkTransport implements WorkTransport {

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(10);

    private final Publisher publisher;

    public GooglePubSubWorkTransport(WorkTransportProperties properties) throws IOException {
        this(Publisher.newBuilder(ProjectTopicName.of(
                        properties.cloudProjectId(), properties.workTopic()))
                .build());
    }

    GooglePubSubWorkTransport(Publisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(WorkMessage message, String workType) {
        PubsubMessage pubsubMessage = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(message.payload()))
                .putAllAttributes(message.attributes())
                .build();
        try {
            publisher.publish(pubsubMessage).get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + workType + " work", exception);
        } catch (TimeoutException | java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("Unable to publish " + workType + " work", exception);
        }
    }

    @PreDestroy
    void close() throws InterruptedException {
        publisher.shutdown();
        publisher.awaitTermination(30, TimeUnit.SECONDS);
    }
}
