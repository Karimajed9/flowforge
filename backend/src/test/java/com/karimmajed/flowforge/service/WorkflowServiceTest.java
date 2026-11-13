package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.WebhookTokenResponse;
import com.karimmajed.flowforge.entity.Workflow;
import com.karimmajed.flowforge.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private WorkflowRepository repository;

    @Mock
    private WebhookTokenService webhookTokenService;

    @InjectMocks
    private WorkflowService service;

    @Test
    void rotateWebhookToken_storesTheHashAndReturnsThePlaintextToken() {
        Workflow workflow = new Workflow("Onboarding", "New hire");
        workflow.setWebhookTokenHash("previous-hash");
        when(repository.findById(4L)).thenReturn(Optional.of(workflow));
        when(webhookTokenService.generateToken()).thenReturn("plain-token");
        when(webhookTokenService.hashToken("plain-token")).thenReturn("new-hash");

        WebhookTokenResponse response = service.rotateWebhookToken(4L);

        InOrder order = inOrder(repository, webhookTokenService);
        order.verify(repository).findById(4L);
        order.verify(webhookTokenService).generateToken();
        order.verify(webhookTokenService).hashToken("plain-token");

        assertThat(response.token()).isEqualTo("plain-token");
        assertThat(field(workflow, "webhookTokenHash")).isEqualTo("new-hash");
    }

    @Test
    void rotateWebhookToken_missingWorkflow_isNotFound() {
        when(repository.findById(4L)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.rotateWebhookToken(4L));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).isEqualTo("Workflow not found");
        verifyNoInteractions(webhookTokenService);
    }

    private static Object field(Object target, String name) {
        try {
            Field declared = target.getClass().getDeclaredField(name);
            declared.setAccessible(true);
            return declared.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
