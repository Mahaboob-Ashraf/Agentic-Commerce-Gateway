package dev.agenticcommerce.gateway.demo;

import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/demo-merchants/{merchantKey}")
public class DemoMerchantApiController {
    private final DemoMerchantService service;
    public DemoMerchantApiController(DemoMerchantService service){this.service=service;}
    @PostMapping("/products/search") public JsonNode search(@PathVariable String merchantKey,@RequestBody JsonNode body){return service.search(merchantKey,body);}
    @PostMapping("/availability") public JsonNode availability(@PathVariable String merchantKey,@RequestBody JsonNode body){return service.availability(merchantKey,body);}
    @PostMapping("/quotes") public JsonNode quote(@PathVariable String merchantKey,@RequestBody JsonNode body){return service.quote(merchantKey,body);}
    @PostMapping("/orders") public JsonNode place(@PathVariable String merchantKey,@RequestBody JsonNode body){return service.placeOrder(merchantKey,body);}
    @GetMapping("/orders/{orderId}") public JsonNode order(@PathVariable String merchantKey,@PathVariable String orderId){return service.order(merchantKey,orderId);}
    @PostMapping("/orders/{orderId}/cancel") public JsonNode cancel(@PathVariable String merchantKey,@PathVariable String orderId){return service.cancel(merchantKey,orderId);}
    @PostMapping("/orders/{orderId}/returns") public JsonNode returns(@PathVariable String merchantKey,@PathVariable String orderId){return service.requestReturn(merchantKey,orderId);}
}
