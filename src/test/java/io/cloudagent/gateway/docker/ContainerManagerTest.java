package io.cloudagent.gateway.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cloudagent.gateway.agent.AgentDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerManagerTest {

    private static final AgentDefinition AGENT =
            new AgentDefinition("cloud-agent:latest", 4321, null, Map.of(), false);

    /** {@link ContainerManager} with the {@code docker} CLI replaced by a recording stub. */
    private static final class RecordingContainerManager extends ContainerManager {

        private final List<List<String>> invocations = new ArrayList<>();
        private final Function<List<String>, List<String>> responder;

        RecordingContainerManager(DockerProperties properties, HostPortAllocator allocator,
                                  Function<List<String>, List<String>> responder) {
            super(properties, allocator);
            this.responder = responder;
        }

        @Override
        List<String> run(String... command) {
            List<String> args = List.of(command);
            if (invocations == null) {
                // Called from the superclass constructor (ensureNetwork) before our fields exist.
                return List.of();
            }
            invocations.add(args);
            return responder == null ? List.of() : responder.apply(args);
        }

        List<String> createCommand() {
            return invocations.stream()
                    .filter(cmd -> cmd.size() > 1 && cmd.get(1).equals("create"))
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }

        List<List<String>> createCommands() {
            return invocations.stream()
                    .filter(cmd -> cmd.size() > 1 && cmd.get(1).equals("create"))
                    .toList();
        }
    }

    private DockerProperties properties(Path workspaceRoot) {
        return new DockerProperties("cloud-agent-net", workspaceRoot.toString(), "cloud-agent", 30, 45600, 45610);
    }

    @Test
    void publishesCopilotPortOnUniqueLoopbackHostPort(@TempDir Path tmp) {
        HostPortAllocator allocator = new HostPortAllocator(properties(tmp));
        RecordingContainerManager manager = new RecordingContainerManager(properties(tmp), allocator, args -> List.of());

        CreatedContainer created = manager.createContainer("session-1", AGENT, false);

        assertThat(created.containerName()).isEqualTo("cloud-agent-session-1");
        assertThat(created.hostPort()).isBetween(45600, 45610);

        List<String> command = manager.createCommand();
        int publishIndex = command.indexOf("--publish");
        assertThat(publishIndex).isPositive();
        assertThat(command.get(publishIndex + 1))
                .isEqualTo("127.0.0.1:" + created.hostPort() + ":4321");
    }

    @Test
    void neverBindsThePublishedPortToAllInterfaces(@TempDir Path tmp) {
        HostPortAllocator allocator = new HostPortAllocator(properties(tmp));
        RecordingContainerManager manager = new RecordingContainerManager(properties(tmp), allocator, args -> List.of());

        manager.createContainer("session-2", AGENT, false);

        List<String> command = manager.createCommand();
        assertThat(command).noneMatch(arg -> arg.contains("0.0.0.0"))
                .noneMatch(arg -> arg.equals("--publish-all") || arg.equals("-P"));
        String publish = command.get(command.indexOf("--publish") + 1);
        assertThat(publish).startsWith(HostPortAllocator.LOOPBACK + ":");
    }

    @Test
    void usesTheContainersConfiguredCopilotPortInsideTheContainer(@TempDir Path tmp) {
        HostPortAllocator allocator = new HostPortAllocator(properties(tmp));
        RecordingContainerManager manager = new RecordingContainerManager(properties(tmp), allocator, args -> List.of());
        AgentDefinition agent = new AgentDefinition("cloud-agent:latest", 9999, null, Map.of(), false);

        CreatedContainer created = manager.createContainer("session-3", agent, false);

        String publish = manager.createCommand().get(manager.createCommand().indexOf("--publish") + 1);
        assertThat(publish).isEqualTo("127.0.0.1:" + created.hostPort() + ":9999");
    }

    @Test
    void keepsTheSharedDockerNetwork(@TempDir Path tmp) {
        HostPortAllocator allocator = new HostPortAllocator(properties(tmp));
        RecordingContainerManager manager = new RecordingContainerManager(properties(tmp), allocator, args -> List.of());

        manager.createContainer("session-4", AGENT, false);

        List<String> command = manager.createCommand();
        assertThat(command.get(command.indexOf("--network") + 1)).isEqualTo("cloud-agent-net");
    }

    @Test
    void retriesWithAnotherPortWhenDockerRejectsTheMapping(@TempDir Path tmp) {
        HostPortAllocator allocator = new HostPortAllocator(properties(tmp));
        // Docker is the authority on availability: fail the first `docker create`, expect a retry
        // with a different host port.
        boolean[] firstCreate = {true};
        RecordingContainerManager manager = new RecordingContainerManager(properties(tmp), allocator, args -> {
            if (args.size() > 1 && args.get(1).equals("create") && firstCreate[0]) {
                firstCreate[0] = false;
                throw new DockerException("port is already allocated");
            }
            return List.of();
        });

        CreatedContainer created = manager.createContainer("session-5", AGENT, false);

        List<List<String>> creates = manager.createCommands();
        assertThat(creates).hasSize(2);
        String firstPublish = creates.get(0).get(creates.get(0).indexOf("--publish") + 1);
        String secondPublish = creates.get(1).get(creates.get(1).indexOf("--publish") + 1);
        assertThat(secondPublish).isNotEqualTo(firstPublish)
                .isEqualTo("127.0.0.1:" + created.hostPort() + ":4321");
    }

    @Test
    void failsWhenDockerKeepsRejectingEveryMapping(@TempDir Path tmp) {
        HostPortAllocator allocator = new HostPortAllocator(properties(tmp));
        RecordingContainerManager manager = new RecordingContainerManager(properties(tmp), allocator, args -> {
            if (args.size() > 1 && args.get(1).equals("create")) {
                throw new DockerException("port is already allocated");
            }
            return List.of();
        });

        assertThatThrownBy(() -> manager.createContainer("session-6", AGENT, false))
                .isInstanceOf(DockerException.class)
                .hasMessageContaining("host port attempts");
    }

    @Test
    void doesNotBurnPortsWhenTheFailureIsUnrelatedToPorts(@TempDir Path tmp) {
        HostPortAllocator allocator = new HostPortAllocator(properties(tmp));
        RecordingContainerManager manager = new RecordingContainerManager(properties(tmp), allocator, args -> {
            if (args.size() > 1 && args.get(1).equals("create")) {
                throw new DockerException("Unable to find image 'cloud-agent:latest' locally");
            }
            return List.of();
        });

        assertThatThrownBy(() -> manager.createContainer("session-9", AGENT, false))
                .isInstanceOf(DockerException.class)
                .hasMessageContaining("Unable to find image");
        assertThat(manager.createCommands()).hasSize(1);
        assertThat(allocator.isReserved(45600)).isFalse();
    }

    @Test
    void readsTheExistingLoopbackMappingBackFromDocker(@TempDir Path tmp) {
        HostPortAllocator allocator = new HostPortAllocator(properties(tmp));
        RecordingContainerManager manager = new RecordingContainerManager(properties(tmp), allocator,
                args -> args.contains("inspect") ? List.of("127.0.0.1:45607") : List.of());

        Optional<Integer> hostPort = manager.findHostPort("cloud-agent-session-7", 4321);

        assertThat(hostPort).contains(45607);
    }

    @Test
    void inspectReportsThePublishedLoopbackHostPort(@TempDir Path tmp) {
        HostPortAllocator allocator = new HostPortAllocator(properties(tmp));
        RecordingContainerManager manager = new RecordingContainerManager(properties(tmp), allocator,
                args -> List.of("abc123|/cloud-agent-session-8|true|running|127.0.0.1:45605,"));

        Optional<ContainerInfo> info = manager.inspectContainer("cloud-agent-session-8");

        assertThat(info).isPresent();
        assertThat(info.get().name()).isEqualTo("cloud-agent-session-8");
        assertThat(info.get().running()).isTrue();
        assertThat(info.get().hostPort()).isEqualTo(45605);
    }
}
