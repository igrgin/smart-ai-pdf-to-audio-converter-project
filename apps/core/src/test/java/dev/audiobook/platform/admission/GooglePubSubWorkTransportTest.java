package dev.audiobook.platform.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.api.core.ApiFuture;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.pubsub.v1.PubsubMessage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class GooglePubSubWorkTransportTest {

    private final Publisher publisher = mock(Publisher.class);
    private final GooglePubSubWorkTransport transport = new GooglePubSubWorkTransport(publisher);
    private final PubsubMessage message = PubsubMessage.getDefaultInstance();

    @Test
    void publishesAndWaitsForBrokerAcceptance() throws Exception {
        ApiFuture<String> future = mock(ApiFuture.class);
        given(publisher.publish(message)).willReturn(future);
        given(future.get(anyLong(), eq(TimeUnit.MILLISECONDS))).willReturn("broker-message-id");

        transport.publish(message, "inspection");

        verify(publisher).publish(message);
        verify(future).get(10_000, TimeUnit.MILLISECONDS);
    }

    @Test
    void reportsPublishTimeoutWithoutLosingTheWorkType() throws Exception {
        ApiFuture<String> future = mock(ApiFuture.class);
        given(publisher.publish(message)).willReturn(future);
        given(future.get(anyLong(), eq(TimeUnit.MILLISECONDS))).willThrow(new TimeoutException("slow"));

        assertThatThrownBy(() -> transport.publish(message, "Narration Plan"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to publish Narration Plan work");
    }

    @Test
    void restoresInterruptStatusWhenPublicationIsCancelled() throws Exception {
        ApiFuture<String> future = mock(ApiFuture.class);
        given(publisher.publish(message)).willReturn(future);
        given(future.get(anyLong(), eq(TimeUnit.MILLISECONDS))).willThrow(new InterruptedException("cancelled"));

        try {
            assertThatThrownBy(() -> transport.publish(message, "inspection"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Interrupted while publishing inspection work");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shutsDownAndAwaitsTheSharedPublisher() throws Exception {
        transport.close();

        verify(publisher).shutdown();
        verify(publisher).awaitTermination(30, TimeUnit.SECONDS);
    }
}
