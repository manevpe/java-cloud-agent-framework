package io.github.manevpe.agentic.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata for the generated Swagger UI (see {@code /swagger-ui.html}). */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI javaCloudAgentFrameworkOpenApi() {
        return new OpenAPI().info(new Info()
                .title("java-cloud-agent-framework")
                .description("Webhook ingress for the semi-autonomous, human-in-the-loop agent workflow engine.")
                .version("v1"));
    }
}
