package com.teggr.codeagent.agent.copilot;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import com.teggr.codeagent.agent.AgentHarness;
import com.teggr.codeagent.agent.AgentHarnessFactory;
import com.teggr.codeagent.agent.AgentHarnessProperties;

/** Connects to the Copilot CLI server, retrying while the runner container is still starting up. */
@Service
public class CopilotAgentHarnessFactory implements AgentHarnessFactory {

    private final AgentHarnessProperties properties;

    public CopilotAgentHarnessFactory(AgentHarnessProperties properties) {
        this.properties = properties;
    }

    @Override
    public AgentHarness connect(int hostPort, Runnable stillStartable) throws Exception {
        int maxRetries = properties.getMaxRetries();
        long retryDelayMs = properties.getRetryDelayMs();
        int startTimeoutSeconds = properties.getStartTimeoutSeconds();
        System.out.println("Connecting to Copilot CLI on host port " + hostPort + " (" + maxRetries + " attempts, "
                + retryDelayMs + "ms apart)");

        for (int i = 0; i < maxRetries; i++) {
            int attempt = i + 1;
            System.out.println("Starting Copilot client (attempt " + attempt + " of " + maxRetries + ")");
            try {
                var options = new CopilotClientOptions()
                    .setCliUrl("localhost:" + hostPort)
                    .setLogLevel(properties.getLogLevel());
                var client = new CopilotClient(options);
                try {
                    client.start().get(startTimeoutSeconds, TimeUnit.SECONDS);
                } catch (Exception e) {
                    client.close();
                    throw e;
                }
                System.out.println("Copilot client connected on attempt " + attempt + " of " + maxRetries);
                return new CopilotAgentHarness(client);
            } catch (Exception e) {
                stillStartable.run();
                if (i == maxRetries - 1) {
                    System.out.println("Copilot client failed after all " + maxRetries + " attempts");
                    throw e;
                }
                System.out.println("Copilot client failed on attempt " + attempt + " of " + maxRetries
                        + "; retrying in " + retryDelayMs + "ms: " + e.getClass().getSimpleName());
                Thread.sleep(retryDelayMs);
            }
        }
        throw new IllegalStateException("Unreachable: maxRetries must be greater than zero");
    }

}
