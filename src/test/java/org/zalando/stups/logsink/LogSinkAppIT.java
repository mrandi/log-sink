package org.zalando.stups.logsink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.rules.SpringClassRule;
import org.springframework.test.context.junit4.rules.SpringMethodRule;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.slf4j.LoggerFactory.getLogger;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = LogSinkApp.class)
@ActiveProfiles("it")
public class LogSinkAppIT {

    @ClassRule
    public static final SpringClassRule SPRING_CLASS_RULE = new SpringClassRule();

    private static final String CORRECT_USER = "it-user";
    private static final String CORRECT_PASSWORD = "t0p5ecr3t";

    private final Logger log = getLogger(getClass());

    @Rule
    public final SpringMethodRule springMethodRule = new SpringMethodRule();

    @Rule
    public final WireMockRule wireMockRule = new WireMockRule(Integer.valueOf(System.getProperty("wiremock.port", "10080")));

    @Value("${local.server.port}")
    private int port;

    @Value("${local.management.port}")
    private int managementPort;

    private String jsonPayload;
    private ImmutableMap<String, Object> payload;

    @Before
    public void setUp() throws Exception {
        payload = ImmutableMap.of(
                "foo", "bar",
                "an_integer", 5,
                "complex_inner_type", singletonMap("hello", "World"));
        jsonPayload = new ObjectMapper().writeValueAsString(payload);
    }

    @Test
    public void testManagementEndpoints() throws Exception {
        final TestRestTemplate restOperations = new TestRestTemplate();
        log.info("ENVIRONMENT:\n{}", restOperations.getForObject("http://localhost:" + managementPort + "/env", String.class));
        log.info("CONFIG_PROPS:\n{}", restOperations.getForObject("http://localhost:" + managementPort + "/configprops", String.class));
        log.info("AUTOCONFIG:\n{}", restOperations.getForObject("http://localhost:" + managementPort + "/autoconfig", String.class));
    }

    @Test
    public void testPushLogs() throws Exception {
        final TestRestTemplate restOperations = new TestRestTemplate(CORRECT_USER, CORRECT_PASSWORD);

        stubFor(post(urlPathEqualTo("/api/instance-logs")).willReturn(aResponse().withStatus(201)));

        final URI url = URI.create("http://localhost:" + port + "/instance-logs");
        final ResponseEntity<String> response = restOperations.exchange(RequestEntity.post(url).contentType(APPLICATION_JSON).body(payload), String.class);
        assertThat(response.getStatusCode()).isEqualTo(CREATED);

        log.debug("Waiting for async tasks to finish");
        TimeUnit.SECONDS.sleep(1);

        verify(postRequestedFor(urlPathEqualTo("/api/instance-logs"))
                .withRequestBody(equalToJson(jsonPayload))
                .withHeader(AUTHORIZATION, equalTo("Bearer 1234567890")));

        log.info("METRICS:\n{}", restOperations.getForObject("http://localhost:" + managementPort + "/metrics", String.class));
    }

    @Test
    public void testPushLogsUnauthenticated() throws Exception {
        final TestRestTemplate restOperations = new TestRestTemplate();

        stubFor(post(urlPathEqualTo("/api/instance-logs")).willReturn(aResponse().withStatus(201)));

        final URI url = URI.create("http://localhost:" + port + "/instance-logs");
        final ResponseEntity<String> response = restOperations.exchange(RequestEntity.post(url).contentType(APPLICATION_JSON).body(payload), String.class);
        assertThat(response.getStatusCode()).isEqualTo(UNAUTHORIZED);
    }

    @Test
    public void testPushLogsWrongPassword() throws Exception {
        final TestRestTemplate restOperations = new TestRestTemplate(CORRECT_USER, "wrong-password");

        stubFor(post(urlPathEqualTo("/api/instance-logs")).willReturn(aResponse().withStatus(201)));

        final URI url = URI.create("http://localhost:" + port + "/instance-logs");
        final ResponseEntity<String> response = restOperations.exchange(RequestEntity.post(url).contentType(APPLICATION_JSON).body(payload), String.class);
        assertThat(response.getStatusCode()).isEqualTo(UNAUTHORIZED);
    }

    @Test
    public void testPushLogsUpstreamFailsOnce() throws Exception {
        final TestRestTemplate restOperations = new TestRestTemplate(CORRECT_USER, CORRECT_PASSWORD);

        // first calls fails
        stubFor(post(urlPathEqualTo("/api/instance-logs")).inScenario("Test Retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("retry"));

        // second one succeeds
        stubFor(post(urlPathEqualTo("/api/instance-logs")).inScenario("Test Retry")
                .whenScenarioStateIs("retry")
                .willReturn(aResponse().withStatus(201)));

        final URI url = URI.create("http://localhost:" + port + "/instance-logs");
        final ResponseEntity<String> response = restOperations.exchange(RequestEntity.post(url).contentType(APPLICATION_JSON).body(payload), String.class);

        // even if upstream request fails, due to async processing this endpoint should return a success message
        assertThat(response.getStatusCode()).isEqualTo(CREATED);

        log.debug("Waiting for async tasks to finish");
        TimeUnit.SECONDS.sleep(2);

        // endpoint should have been called twice
        verify(2, postRequestedFor(urlPathEqualTo("/api/instance-logs"))
                .withRequestBody(equalToJson(jsonPayload))
                .withHeader(AUTHORIZATION, equalTo("Bearer 1234567890")));
    }

    @Test
    public void testInvokeRootEndpoint() throws Exception {
        final TestRestTemplate restOperations = new TestRestTemplate();

        final URI url = URI.create("http://localhost:" + port);
        final ResponseEntity<String> response = restOperations.exchange(RequestEntity.get(url).build(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(UNAUTHORIZED);
    }
}
