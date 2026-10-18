package dev.relaybox.api;

import dev.relaybox.service.WebhookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class WebhookController {
    private final WebhookService service;

    public WebhookController(WebhookService service) { this.service = service; }

    @PostMapping("/endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.EndpointResponse createEndpoint(@RequestAttribute("tenantId") UUID tenantId,
                                                     @Valid @RequestBody ApiModels.CreateEndpointRequest request) {
        return service.createEndpoint(tenantId, request);
    }

    @GetMapping("/endpoints")
    public List<ApiModels.EndpointResponse> listEndpoints(@RequestAttribute("tenantId") UUID tenantId) {
        return service.listEndpoints(tenantId);
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiModels.PublishEventResponse publish(@RequestAttribute("tenantId") UUID tenantId,
                                                  @Valid @RequestBody ApiModels.PublishEventRequest request) {
        return service.publish(tenantId, request);
    }

    @GetMapping("/events/{eventId}/deliveries")
    public List<ApiModels.DeliveryResponse> deliveries(@RequestAttribute("tenantId") UUID tenantId,
                                                       @PathVariable UUID eventId) {
        return service.listDeliveries(tenantId, eventId);
    }

    @GetMapping("/deliveries/{deliveryId}/attempts")
    public List<ApiModels.DeliveryAttemptResponse> attempts(@RequestAttribute("tenantId") UUID tenantId,
                                                            @PathVariable UUID deliveryId) {
        return service.listAttempts(tenantId, deliveryId);
    }

    @PostMapping("/deliveries/{deliveryId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void replay(@RequestAttribute("tenantId") UUID tenantId, @PathVariable UUID deliveryId) {
        service.replay(tenantId, deliveryId);
    }
}
