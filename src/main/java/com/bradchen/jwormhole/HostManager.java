package com.bradchen.jwormhole;

import org.apache.commons.lang3.RandomStringUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class HostManager {

	private static final int NUM_KEY_CHARACTERS = 5;
	public static final int PORT_RANGE_START = 20000;
	public static final int PORT_RANGE_END = 30000;

	private final ScheduledExecutorService scheduler;
	private final ReadWriteLock readWriteLock;
	private final Set<Integer> ports;
	private final Map<String, Host> hosts;

	public HostManager() {
		readWriteLock = new ReentrantReadWriteLock();
		ports = new HashSet<>();
		hosts = new ConcurrentHashMap<>();
		scheduler = Executors.newScheduledThreadPool(1);
		scheduler.scheduleAtFixedRate(this::removeExpiredHosts, 1, 1, TimeUnit.MINUTES);
	}

	public Host getHost(String key) {
		readWriteLock.readLock().lock();
		try {
			return hosts.get(key);
		} finally {
			readWriteLock.readLock().unlock();
		}
	}

	public Host createHost() {
		readWriteLock.writeLock().lock();
		try {
			String key;
			do {
				key = RandomStringUtils.randomAlphanumeric(NUM_KEY_CHARACTERS).toLowerCase();
			} while (hosts.containsKey(key));
			int port;
			do {
				port = (int)(Math.random() * (PORT_RANGE_END - PORT_RANGE_START)
					+ PORT_RANGE_START);
			} while (ports.contains(port));
			Host host = new Host(key, port);
			ports.add(port);
			hosts.put(key, host);
			return host;
		} finally {
			readWriteLock.writeLock().unlock();
		}
	}

	private void removeExpiredHosts() {
		readWriteLock.writeLock().lock();
		try {
			hosts.entrySet().stream().forEach(entry -> {
				if (entry.getValue().isExpired()) {
					ports.remove(entry.getValue().getPort());
					hosts.remove(entry.getKey());
				}
			});
		} finally {
			readWriteLock.writeLock().unlock();
		}
	}

	public void shutdown() {
		scheduler.shutdown();
	}

}
