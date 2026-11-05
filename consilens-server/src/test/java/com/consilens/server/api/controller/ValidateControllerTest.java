package com.consilens.server.api.controller;

import com.consilens.server.api.advice.GlobalExceptionHandler;
import com.consilens.server.application.capability.SynchronousCapabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ValidateControllerTest {

    @Test
    void shouldRejectValidateRequestWithoutConfigReference() throws Exception {
        SynchronousCapabilityService service = mock(SynchronousCapabilityService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ValidateController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();

        mockMvc.perform(post("/v1/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-validate")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        verify(service, never()).validate(any(), any());
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
