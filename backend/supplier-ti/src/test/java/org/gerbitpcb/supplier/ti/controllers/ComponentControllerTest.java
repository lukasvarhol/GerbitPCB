package org.gerbitpcb.supplier.ti.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gerbitpcb.supplier.ti.domain.ReservationRequest;
import org.gerbitpcb.supplier.ti.domain.ReserveRequest;
import org.gerbitpcb.supplier.ti.services.ComponentService;
import org.gerbitpcb.supplier.ti.repository.ComponentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ComponentController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class})
class ComponentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ComponentService componentService;

    @MockitoBean
    private ComponentRepository componentRepository;

    @Test
    void testReserveEndpoint_Success() throws Exception {
        UUID mockId = UUID.randomUUID();
        when(componentService.reserve(anyString(), anyInt())).thenReturn(mockId);

        ReserveRequest payload = new ReserveRequest("NE555P", 10);

        mockMvc.perform(post("/api/transaction/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(mockId.toString()));
    }

    @Test
    void testCommitEndpoint_Success() throws Exception {
        UUID mockId = UUID.randomUUID();
        doNothing().when(componentService).commit(mockId);

        ReservationRequest payload = new ReservationRequest(mockId);

        mockMvc.perform(post("/api/transaction/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void testRollbackEndpoint_Success() throws Exception {
        UUID mockId = UUID.randomUUID();
        doNothing().when(componentService).rollback(mockId);

        ReservationRequest payload = new ReservationRequest(mockId);

        mockMvc.perform(post("/api/transaction/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is2xxSuccessful());
    }
}
