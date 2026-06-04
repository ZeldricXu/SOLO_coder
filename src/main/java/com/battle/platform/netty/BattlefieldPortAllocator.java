package com.battle.platform.netty;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

@Slf4j
@Component
public class BattlefieldPortAllocator {

    private static final int PORT_RANGE_START = 10000;
    private static final int PORT_RANGE_END = 19999;
    private static final int MAX_RETRY = 5;

    private final Set<Integer> allocatedPorts = new HashSet<>();
    private final Random random = new Random();

    public synchronized int allocatePort() throws PortAllocationException {
        int retry = 0;

        while (retry < MAX_RETRY) {
            int port = generateRandomPort();

            if (allocatedPorts.contains(port)) {
                retry++;
                continue;
            }

            if (isPortAvailable(port)) {
                allocatedPorts.add(port);
                log.info("Allocated port {} for battlefield instance", port);
                return port;
            }

            retry++;
            log.warn("Port {} unavailable, retrying... ({}/{})", port, retry, MAX_RETRY);
        }

        throw new PortAllocationException("Failed to allocate port after " + MAX_RETRY + " retries");
    }

    public synchronized void releasePort(int port) {
        allocatedPorts.remove(port);
        log.info("Released port {}", port);
    }

    public synchronized boolean isAllocated(int port) {
        return allocatedPorts.contains(port);
    }

    private int generateRandomPort() {
        return PORT_RANGE_START + random.nextInt(PORT_RANGE_END - PORT_RANGE_START + 1);
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static class PortAllocationException extends Exception {
        public PortAllocationException(String message) {
            super(message);
        }
    }
}
