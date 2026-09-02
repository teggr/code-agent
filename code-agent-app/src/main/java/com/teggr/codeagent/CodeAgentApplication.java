package com.teggr.codeagent;

import java.util.concurrent.CompletableFuture;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.github.copilot.CopilotClient;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;

@SpringBootApplication
public class CodeAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeAgentApplication.class, args);
    }

    @Bean
    ApplicationRunner applicationRunner() {
        return args -> {
            // Application startup logic here

            System.out.println("Getting client");

            var options = new CopilotClientOptions()
    .setCliUrl("localhost:4321");


            try (var client = new CopilotClient(options)) {

                System.out.println("Starting client");

                client.start().get();

                System.out.println("Creating session");
                
                var session = client.createSession(
                    new SessionConfig()
                        .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
                        //.setModel("Auto")

                ).get();

                System.out.println("Sending message");

                var done = new CompletableFuture<Void>();
                session.on(AssistantMessageEvent.class, msg -> {
                    System.out.println(msg.getData().content());
                });
                session.on(SessionIdleEvent.class, idle -> done.complete(null));

                session.send(new MessageOptions().setPrompt("What is 2+2?")).get();

                done.get();
            
                System.out.println("Done");
            }

        };
    }

}
