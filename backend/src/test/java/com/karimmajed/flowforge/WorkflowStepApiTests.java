package com.karimmajed.flowforge;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkflowStepApiTests {

    private static final long MISSING_WORKFLOW_ID = 9_000_000_000L;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void create_storesBothStepTypesAndListsByPosition() throws Exception {
        long workflowId = createWorkflow();

        mockMvc.perform(post("/api/workflows/{workflowId}/steps", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepJson("Call API", "HTTP_REQUEST", 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Call API"))
                .andExpect(jsonPath("$.stepType").value("HTTP_REQUEST"))
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.id").isNumber());

        mockMvc.perform(post("/api/workflows/{workflowId}/steps", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepJson("Validate", "VALIDATE_FIELDS", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Validate"))
                .andExpect(jsonPath("$.stepType").value("VALIDATE_FIELDS"))
                .andExpect(jsonPath("$.position").value(1));

        mockMvc.perform(get("/api/workflows/{workflowId}/steps", workflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Validate"))
                .andExpect(jsonPath("$[0].stepType").value("VALIDATE_FIELDS"))
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[1].name").value("Call API"))
                .andExpect(jsonPath("$[1].stepType").value("HTTP_REQUEST"))
                .andExpect(jsonPath("$[1].position").value(2));
    }

    @Test
    void list_isEmptyWhenWorkflowHasNoSteps() throws Exception {
        long workflowId = createWorkflow();

        mockMvc.perform(get("/api/workflows/{workflowId}/steps", workflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void list_doesNotIncludeStepsFromAnotherWorkflow() throws Exception {
        long first = createWorkflow();
        long second = createWorkflow();
        createStep(first, "Only on first", "HTTP_REQUEST", 1);

        mockMvc.perform(get("/api/workflows/{workflowId}/steps", second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/workflows/{workflowId}/steps", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Only on first"))
                .andExpect(jsonPath("$[0].stepType").value("HTTP_REQUEST"));
    }

    @Test
    void create_missingWorkflow_isNotFound() throws Exception {
        mockMvc.perform(post("/api/workflows/{workflowId}/steps", MISSING_WORKFLOW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepJson("Validate", "VALIDATE_FIELDS", 1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_missingWorkflow_isNotFound() throws Exception {
        mockMvc.perform(get("/api/workflows/{workflowId}/steps", MISSING_WORKFLOW_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_duplicatePosition_isConflict() throws Exception {
        long workflowId = createWorkflow();
        createStep(workflowId, "First", "VALIDATE_FIELDS", 1);

        mockMvc.perform(post("/api/workflows/{workflowId}/steps", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepJson("Second", "HTTP_REQUEST", 1)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/workflows/{workflowId}/steps", workflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("First"))
                .andExpect(jsonPath("$[0].stepType").value("VALIDATE_FIELDS"));
    }

    @ParameterizedTest
    @MethodSource("invalidStepBodies")
    void create_rejectsInvalidStep(String body) throws Exception {
        mockMvc.perform(post("/api/workflows/{workflowId}/steps", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private long createWorkflow() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"steps-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.id");
        return id.longValue();
    }

    private void createStep(long workflowId, String name, String stepType, int position) throws Exception {
        mockMvc.perform(post("/api/workflows/{workflowId}/steps", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepJson(name, stepType, position)))
                .andExpect(status().isCreated());
    }

    private static String stepJson(String name, String stepType, int position) {
        return "{\"name\":\"" + name + "\",\"stepType\":\"" + stepType
                + "\",\"position\":" + position + "}";
    }

    private static Stream<String> invalidStepBodies() {
        return Stream.of(
                "{\"name\":\" \",\"stepType\":\"VALIDATE_FIELDS\",\"position\":1}",
                "{\"name\":\"\",\"stepType\":\"HTTP_REQUEST\",\"position\":1}",
                "{\"name\":\"Check\",\"stepType\":\"VALIDATE_FIELDS\",\"position\":0}",
                "{\"name\":\"Check\",\"stepType\":\"VALIDATE_FIELDS\",\"position\":-1}",
                "{\"name\":\"Check\",\"stepType\":\"VALIDATE_FIELDS\"}",
                "{\"name\":\"Check\",\"position\":1}",
                "{\"name\":\"Check\",\"stepType\":null,\"position\":1}",
                "{\"name\":\"Check\",\"stepType\":\"SEND_EMAIL\",\"position\":1}",
                "{\"stepType\":\"HTTP_REQUEST\",\"position\":1}",
                "{\"name\":\"" + "n".repeat(151) + "\",\"stepType\":\"HTTP_REQUEST\",\"position\":1}"
        );
    }
}
