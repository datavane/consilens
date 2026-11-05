package com.consilens.server.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "consilens.server.scheduler.enabled=false",
                "management.endpoint.health.show-details=always"
        })
@ActiveProfiles("local")
class ActuatorHealthProbeIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldExposeLivenessAndReadinessProbes() {
        assertProbeUp("/actuator/health/liveness");
        assertProbeUp("/actuator/health/readiness");
        assertProbeUp("/actuator/health");

        ResponseEntity<String> readiness = restTemplate.getForEntity(
                "/actuator/health/readiness", String.class);
        assertThat(readiness.getBody()).contains("\"db\":{\"status\":\"UP\"");
        assertThat(readiness.getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(readiness.getHeaders().getFirst("X-Frame-Options"))
                .isEqualTo("SAMEORIGIN");

        ResponseEntity<String> info = restTemplate.getForEntity("/actuator/info", String.class);
        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(info.getBody()).contains("\"build\"");
        assertThat(info.getBody()).contains("\"version\"");
    }

    private void assertProbeUp(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as(path).contains("\"status\":\"UP\"");
    }
}
