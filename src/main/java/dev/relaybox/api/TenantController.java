package dev.relaybox.api;

import dev.relaybox.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {
    private final TenantService tenants;

    public TenantController(TenantService tenants) { this.tenants = tenants; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.TenantResponse create(@Valid @RequestBody ApiModels.CreateTenantRequest request) {
        return tenants.create(request);
    }
}
