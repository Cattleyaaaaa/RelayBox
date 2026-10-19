package dev.relaybox.delivery.worker;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class DeliveryJsonCodec {
    private final ObjectMapper mapper;
    public DeliveryJsonCodec(ObjectMapper mapper) { this.mapper = mapper; }
    public String serialize(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("unable to encode delivery body", exception); }
    }
}
