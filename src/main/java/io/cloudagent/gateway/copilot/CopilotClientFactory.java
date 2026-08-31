package io.cloudagent.gateway.copilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientMode;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.RuntimeConnection;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates {@link CopilotClient} instances that attach to an already-running, headless Copilot CLI
 * process using the SDK's {@code RuntimeConnection.forUri(...)} mechanism. The gateway never
 * spawns a CLI process itself: {@code mode(EMPTY)} plus a {@code cliUrl} pointing at the agent
 * container's hostname/port is what makes this an "external server" connection rather than the
 * SDK's default auto-managed child-process mode.
 */
@Component
public class CopilotClientFactory {

    private static final Logger log = LoggerFactory.getLogger(CopilotClientFactory.class);

    private final CopilotConnectionProperties properties;

    public CopilotClientFactory(CopilotConnectionProperties properties) {
        this.properties = properties;
    }

    /**
     * Connects to the headless Copilot CLI listening at {@code host:port}, retrying until it
     * becomes reachable or {@link CopilotConnectionProperties#connectTimeoutSeconds()} elapses.
     */
    public CopilotClient connect(String host, int port) {
        String cliUrl = host + ":" + port;
        CopilotClientOptions options = new CopilotClientOptions()
                .setMode(CopilotClientMode.EMPTY)
                .setConnection(RuntimeConnection.forUri(cliUrl))
                .setAutoStart(false)
                .setAutoRestart(false);

        Duration deadline = Duration.ofSeconds(properties.connectTimeoutSeconds());
        long deadlineMillis = System.currentTimeMillis() + deadline.toMillis();
        Exception lastError = null;

        while (System.currentTimeMillis() < deadlineMillis) {
            CopilotClient client = new CopilotClient(options);
            try {
                CompletableFuture<Void> started = client.start();
                started.get(properties.pollIntervalMillis() * 4, TimeUnit.MILLISECONDS);
                log.info("Connected to headless Copilot CLI at {}", cliUrl);
                return client;
            } catch (ExecutionException | TimeoutException | InterruptedException e) {
                lastError = e;
                client.close();
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                sleep(properties.pollIntervalMillis());
            }
        }
        throw new CopilotConnectionException("Timed out connecting to Copilot CLI at " + cliUrl, lastError);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
