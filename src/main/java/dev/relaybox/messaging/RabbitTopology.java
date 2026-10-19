package dev.relaybox.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {
    public static final String EXCHANGE = "relaybox.events";
    public static final String EVENT_ACCEPTED = "event.accepted";

    @Bean
    TopicExchange relayBoxEventExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }
}
