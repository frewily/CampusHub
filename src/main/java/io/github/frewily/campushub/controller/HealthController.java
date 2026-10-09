package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.service.ReadinessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/health")
public class HealthController {
    private final ReadinessService readiness;
    public HealthController(ReadinessService readiness) { this.readiness = readiness; }
    @GetMapping("/live") public Map<String,String> live() { return Collections.singletonMap("status", "UP"); }
    @GetMapping("/ready") public ResponseEntity<Map<String,String>> ready() {
        boolean ready = readiness.ready();
        return ResponseEntity.status(ready ? 200 : 503).body(Collections.singletonMap("status", ready ? "UP" : "DOWN"));
    }
}
