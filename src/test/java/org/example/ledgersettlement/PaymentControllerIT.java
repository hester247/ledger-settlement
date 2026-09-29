
package org.example.ledgersettlement;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
@Testcontainers
class PaymentControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void paymentOf128450ShouldProduceSettlementOf124469() {

        String requestBody = """
                {
                    "merchantId": "MR-4471",
                    "amountMinor": 128450,
                    "currency": "GBP"
                }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request =
                new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> postResponse =
                restTemplate.postForEntity(
                        "/payments",
                        request,
                        String.class
                );

        assertThat(postResponse.getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> settlementResponse =
                restTemplate.getForEntity(
                        "/payments/settlement?merchantId=MR-4471",
                        String.class
                );

        assertThat(settlementResponse.getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(settlementResponse.getBody())
                .contains("124469");
    }

    @Test
    void negativePaymentAmountShouldReturnBadRequest() {

        String requestBody = """
                {
                    "merchantId": "MR-4471",
                    "amountMinor": -100,
                    "currency": "GBP"
                }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request =
                new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/payments",
                        request,
                        String.class
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankMerchantIdShouldReturnBadRequest() {

        String requestBody = """
                {
                    "merchantId": "",
                    "amountMinor": 1000,
                    "currency": "GBP"
                }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request =
                new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/payments",
                        request,
                        String.class
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownMerchantShouldReturnNotFound() {

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "/payments/settlement?merchantId=UNKNOWN-999",
                        String.class
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}