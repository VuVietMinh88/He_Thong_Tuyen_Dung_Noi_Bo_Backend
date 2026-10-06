package vn.ttcs.recruitment;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import vn.ttcs.recruitment.config.ApiCorsConfiguration;
import vn.ttcs.recruitment.controller.HealthController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthApiTest {
    @Configuration
    @EnableWebMvc
    @Import({HealthController.class, ApiCorsConfiguration.class})
    static class MvcConfiguration {}

    private AnnotationConfigWebApplicationContext context(String origins) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "app.cors.allowed-origins=" + origins);
        context.register(MvcConfiguration.class);
        context.refresh();
        return context;
    }

    @Test
    void healthReturnsContractAndAllowsFrontendOrigin() throws Exception {
        try (var context = context("http://localhost:5173")) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/health").header("Origin", "http://localhost:5173"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(content().json("{\"status\":\"UP\"}"))
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
    }

    @Test
    void preflightAllowsGetAndRejectsOtherOriginsAndMethods() throws Exception {
        try (var context = context("http://localhost:5173")) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(options("/api/health").header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "Accept"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
            mvc.perform(get("/api/health").header("Origin", "https://untrusted.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            mvc.perform(options("/api/health").header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void productionOriginsCanReplaceLocalhost() throws Exception {
        try (var context = context("https://recruitment.example, https://staff.example")) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/health").header("Origin", "https://staff.example"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "https://staff.example"));
            mvc.perform(get("/api/health").header("Origin", "http://localhost:5173"))
                    .andExpect(status().isForbidden());
        }
    }
}
