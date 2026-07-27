package io.github.manevpe.agentic.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the real {@link ChatModel} bean for the {@code github-models}
 * provider — GitHub's officially supported, OpenAI-API-compatible
 * inference endpoint (<a href="https://models.github.ai">models.github.ai</a>),
 * authenticated with a GitHub personal access token. Mirrors
 * {@link GoogleGenAiAutoConfiguration}: {@code SpringAiLlmClient} depends
 * only on the resulting provider-agnostic {@link ChatModel} bean, so this
 * is a self-contained sibling config, not a change to the port or its
 * generic adapter.
 *
 * <p>Builds the OpenAI Java SDK's {@link OpenAIClient} by hand (pointed at
 * GitHub Models' base URL) rather than depending on any Copilot-specific
 * or unofficial client — GitHub Copilot's own internal completions
 * endpoint is not an officially supported third-party/backend API. See
 * {@link GitHubModelsProperties}'s Javadoc for that distinction.
 *
 * <p>Active only when both {@code agentic.llm.enabled=true} and
 * {@code agentic.llm.provider=github-models}. Like
 * {@code GoogleGenAiAutoConfiguration}, Spring AI's own OpenAI starter
 * autoconfiguration is unconditionally excluded (see
 * {@code application.yml}'s {@code spring.autoconfigure.exclude}) since it
 * eagerly builds a {@code ChatModel} bean at context-startup time and would
 * fail hard without provider credentials configured.
 */
@Configuration
@EnableConfigurationProperties(GitHubModelsProperties.class)
@ConditionalOnProperty(prefix = "agentic.llm", name = "enabled", havingValue = "true")
public class GitHubModelsAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "agentic.llm", name = "provider", havingValue = "github-models")
    public ChatModel chatModel(GitHubModelsProperties properties) {
        ClientOptions clientOptions = ClientOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.token())
                .httpClient(SpringAiOpenAiHttpClient.builder().build())
                .build();
        OpenAIClient openAiClient = new OpenAIClientImpl(clientOptions);
        // OpenAiChatModel.Builder also needs an async client for streaming
        // and falls back to environment-variable credential auto-detection
        // (OPENAI_API_KEY, etc.) if one isn't supplied explicitly — building
        // it by hand from the same ClientOptions avoids that fallback ever
        // kicking in.
        OpenAIClientAsync openAiClientAsync = new OpenAIClientAsyncImpl(clientOptions);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.model())
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(options)
                .build();
    }
}
