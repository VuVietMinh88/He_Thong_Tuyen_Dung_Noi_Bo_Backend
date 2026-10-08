package vn.ttcs.recruitment.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.common.ApiError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class JsonSecurityErrors {

    private final ObjectMapper objectMapper;

    public JsonSecurityErrors(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, 401, ApiError.of("UNAUTHORIZED",
                "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại."));
    }

    public void forbidden(HttpServletResponse response) throws IOException {
        write(response, 403, ApiError.of("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này."));
    }

    private void write(HttpServletResponse response, int status, ApiError error) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
