package com.karimmajed.flowforge.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookTokenServiceTest {

    private final WebhookTokenService service = new WebhookTokenService();

    @Test
    void hashToken_isLowercaseSha256Hex() {
        assertThat(service.hashToken("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashToken_isStableAndDiffersByToken() {
        assertThat(service.hashToken("token-1"))
                .isEqualTo(service.hashToken("token-1"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");

        assertThat(service.hashToken("token-1"))
                .isNotEqualTo(service.hashToken("token-2"));
    }

    @Test
    void generateToken_isUrlSafeAndNotStoredAsItself() {
        String first = service.generateToken();
        String second = service.generateToken();

        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(second).matches("[A-Za-z0-9_-]{43}");
        assertThat(first).isNotEqualTo(second);
        assertThat(service.hashToken(first))
                .isNotEqualTo(first)
                .matches("[0-9a-f]{64}");
    }
}
