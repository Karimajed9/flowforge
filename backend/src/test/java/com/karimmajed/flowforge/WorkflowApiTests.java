package com.karimmajed.flowforge;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkflowApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void create_returnsInactiveWorkflow() throws Exception {
        String name = uniqueName();

        mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workflowJson(name, "Ship the first flow")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.description").value("Ship the first flow"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.createdAt", matchesPattern("\\d{4}-\\d{2}-\\d{2}T.+")));
    }

    @Test
    void create_allowsMissingDescription() throws Exception {
        String name = uniqueName();
        long id = createWorkflow(name, null);

        mockMvc.perform(get("/api/workflows/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void list_includesCreatedWorkflow() throws Exception {
        String name = uniqueName();
        createWorkflow(name, "Listed");

        mockMvc.perform(get("/api/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem(name)))
                .andExpect(jsonPath("$[?(@.name == '" + name + "')].description", hasItem("Listed")))
                .andExpect(jsonPath("$[?(@.name == '" + name + "')].active", hasItem(false)));
    }

    @Test
    void get_returnsWorkflow() throws Exception {
        String name = uniqueName();
        long id = createWorkflow(name, "Fetched");

        MvcResult result = mockMvc.perform(get("/api/workflows/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.description").value("Fetched"))
                .andExpect(jsonPath("$.active").value(false))
                .andReturn();

        assertEquals(id, idOf(result));
    }

    @Test
    void get_missingWorkflow_isNotFound() throws Exception {
        mockMvc.perform(get("/api/workflows/{id}", 9_000_000_000L))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStatus_activatesAndDeactivates() throws Exception {
        String name = uniqueName();
        long id = createWorkflow(name, "Toggle me");

        MvcResult activated = mockMvc.perform(patch("/api/workflows/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.description").value("Toggle me"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        assertEquals(id, idOf(activated));

        mockMvc.perform(get("/api/workflows/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(patch("/api/workflows/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/workflows/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void updateStatus_missingWorkflow_isNotFound() throws Exception {
        mockMvc.perform(patch("/api/workflows/{id}/status", 9_000_000_000L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @MethodSource("invalidWorkflowBodies")
    void create_rejectsInvalidWorkflow(String body) throws Exception {
        mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"active\":null}"})
    void updateStatus_rejectsMissingActive(String body) throws Exception {
        long id = createWorkflow(uniqueName(), null);

        mockMvc.perform(patch("/api/workflows/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private long createWorkflow(String name, String description) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workflowJson(name, description)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private static long idOf(MvcResult result) throws Exception {
        Number id = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.id");
        return id.longValue();
    }

    private static String workflowJson(String name, String description) {
        if (description == null) {
            return "{\"name\":\"" + name + "\"}";
        }
        return "{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}";
    }

    private static String uniqueName() {
        return "wf-" + UUID.randomUUID();
    }

    private static Stream<String> invalidWorkflowBodies() {
        return Stream.of(
                "{\"name\":\"\"}",
                "{\"name\":\"   \"}",
                "{\"description\":\"missing name\"}",
                "{\"name\":\"" + "a".repeat(151) + "\"}"
        );
    }
}
