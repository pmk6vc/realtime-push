package testutils;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.*;

import java.util.Map;

@Requires(env = "test")
@Controller("/__test")
public class TestEchoController {

    @Get("/whoami")
    public Map<String, Object> whoami(@Header(name = "x-user-id", defaultValue = "") String xUserId) {
        return Map.of("xUserId", xUserId);
    }
}
