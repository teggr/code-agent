package io.cloudagent.gateway.docker;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Hands out unique loopback host ports used to publish each agent container's Copilot port.
 *
 * <p>Allocation is best-effort by design: the port is probed by binding a {@link ServerSocket} on
 * 127.0.0.1 (which rejects ports already used by another process) and then recorded in an
 * in-process reservation set (which prevents two concurrent session creations picking the same
 * port). The socket is closed before {@code docker create} runs, so a tiny race window remains —
 * Docker itself is the authoritative check: if the publish fails, {@link ContainerManager} asks
 * for another port and retries.
 */
@Component
public class HostPortAllocator {

    /** Address every agent port is published on; never 0.0.0.0, so the port stays host-private. */
    public static final String LOOPBACK = "127.0.0.1";

    private final DockerProperties properties;
    private final Set<Integer> reserved = ConcurrentHashMap.newKeySet();

    public HostPortAllocator(DockerProperties properties) {
        this.properties = properties;
    }

    /**
     * Reserves a currently-free loopback port from the configured range.
     *
     * @throws DockerException if no free port could be found in the configured range
     */
    public int allocate() {
        int from = properties.hostPortRangeStart();
        int to = properties.hostPortRangeEnd();
        for (int port = from; port <= to; port++) {
            if (reserved.add(port)) {
                if (isFree(port)) {
                    return port;
                }
                reserved.remove(port);
            }
        }
        throw new DockerException("No free host port available in range " + from + "-" + to);
    }

    /**
     * Marks a port as in use without probing it, used when reconnecting to an existing container
     * that already owns a published port (see requirement: never re-map a persistent session).
     */
    public void reserve(int port) {
        reserved.add(port);
    }

    /** Releases a previously allocated port so it can be handed out again. */
    public void release(int port) {
        reserved.remove(port);
    }

    public boolean isReserved(int port) {
        return reserved.contains(port);
    }

    private boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), port), 1);
            return true;
        } catch (UnknownHostException e) {
            throw new DockerException("Unable to resolve " + LOOPBACK, e);
        } catch (IOException e) {
            return false;
        }
    }
}
