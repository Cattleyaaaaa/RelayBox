package dev.relaybox.delivery.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class RabbitTopology {
    public static final String EXCHANGE = "relaybox.events";
    public static final String ROUTING_KEY = "event.accepted";
    public static final String QUEUE = "relaybox.delivery.events";
    public static final String DEAD_EXCHANGE = "relaybox.events.dlx";
    public static final String DEAD_QUEUE = "relaybox.delivery.events.dead";

    @Bean
    TopicExchange relayBoxEventExchange() { return new TopicExchange(EXCHANGE, true, false); }

    @Bean
    DirectExchange relayBoxDeadLetterExchange() { return new DirectExchange(DEAD_EXCHANGE, true, false); }

    @Bean
    Queue relayBoxDeliveryQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "event.failed")
                .withArgument("x-single-active-consumer", true)
                .build();
    }

    @Bean
    Queue relayBoxDeadLetterQueue() { return QueueBuilder.durable(DEAD_QUEUE).build(); }

    @Bean
    Binding deliveryBinding(@Qualifier("relayBoxDeliveryQueue") Queue relayBoxDeliveryQueue,
                            TopicExchange relayBoxEventExchange) {
        return BindingBuilder.bind(relayBoxDeliveryQueue).to(relayBoxEventExchange).with(ROUTING_KEY);
    }

    @Bean
    Binding deadLetterBinding(@Qualifier("relayBoxDeadLetterQueue") Queue relayBoxDeadLetterQueue,
                              DirectExchange relayBoxDeadLetterExchange) {
        return BindingBuilder.bind(relayBoxDeadLetterQueue).to(relayBoxDeadLetterExchange).with("event.failed");
    }
}
