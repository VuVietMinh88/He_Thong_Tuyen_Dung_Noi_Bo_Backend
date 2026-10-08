package vn.ttcs.recruitment.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("versionedHealthController")
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public HealthResponse getHealth() {
        return new HealthResponse("UP", "Backend tuyển dụng đang hoạt động.");
    }
}
