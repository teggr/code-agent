package io.cloudagent.gateway.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HostPortAllocatorTest {

    private static DockerProperties properties(int from, int to) {
        return new DockerProperties("cloud-agent-net", "./data/workspaces", "cloud-agent", 30, from, to);
    }

    @Test
    void allocatesPortsWithinConfiguredRange() {
        HostPortAllocator allocator = new HostPortAllocator(properties(45500, 45510));

        int port = allocator.allocate();

        assertThat(port).isBetween(45500, 45510);
        assertThat(allocator.isReserved(port)).isTrue();
    }

    @Test
    void allocatesUniquePortsForConcurrentSessions() {
        HostPortAllocator allocator = new HostPortAllocator(properties(45520, 45530));
        Set<Integer> ports = new HashSet<>();

        for (int i = 0; i < 5; i++) {
            assertThat(ports.add(allocator.allocate())).isTrue();
        }
    }

    @Test
    void releasedPortsCanBeAllocatedAgain() {
        HostPortAllocator allocator = new HostPortAllocator(properties(45540, 45540));
        int port = allocator.allocate();

        assertThatThrownBy(allocator::allocate).isInstanceOf(DockerException.class);

        allocator.release(port);
        assertThat(allocator.allocate()).isEqualTo(port);
    }

    @Test
    void reservedPortIsNotHandedOutAgain() {
        HostPortAllocator allocator = new HostPortAllocator(properties(45550, 45551));
        allocator.reserve(45550);

        assertThat(allocator.allocate()).isEqualTo(45551);
    }
}
