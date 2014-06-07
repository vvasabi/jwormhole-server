package com.bradchen.jwormhole.server;

import org.apache.commons.lang3.RandomStringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manage jWormhole clients and GC expired ones.
 */
public class HostManager {

	private static final String PORT_PATTERN = "(:[\\d]+)?$";

	private final Settings settings;
	private final ScheduledExecutorService scheduler;
	private final ReadWriteLock readWriteLock;
	private final Set<Integer> ports;
	private final Map<String, Host> hosts;

	public HostManager(Settings settings) {
		this.settings = settings;
		readWriteLock = new ReentrantReadWriteLock();
		ports = new HashSet<>();
		hosts = new ConcurrentHashMap<>();
		scheduler = Executors.newScheduledThreadPool(1);
		scheduler.scheduleAtFixedRate(this::removeExpiredHosts, settings.getHostManagerGcInterval(),
			settings.getHostManagerGcInterval(), TimeUnit.SECONDS);
	}

	public Map<String, Host> getHosts() {
		readWriteLock.readLock().lock();
		try {
			return Collections.unmodifiableMap(hosts);
		} finally {
			readWriteLock.readLock().unlock();
		}
	}

	public Host getHost(String domainName) {
		if (domainName == null) {
			return null;
		}

		readWriteLock.readLock().lock();
		try {
			String portRemoved = domainName.replaceAll(PORT_PATTERN, "");
			String name = getNameFromDomainName(portRemoved.toLowerCase());
			if (name == null) {
				return null;
			}
			return hosts.get(name);
		} finally {
			readWriteLock.readLock().unlock();
		}
	}

	public String getNameFromDomainName(String domainName) {
		int prefixLength = settings.getDomainNamePrefix().length();
		int suffixLength = settings.getDomainNameSuffix().length();
		int domainNameLength = domainName.length();
		if (domainNameLength <= (prefixLength + suffixLength)) {
			return null;
		}
		return domainName.substring(prefixLength, domainNameLength - prefixLength - suffixLength);
	}

	public Host createHost() {
		readWriteLock.writeLock().lock();
		try {
			String name;
			do {
				name = RandomStringUtils.randomAlphanumeric(settings.getHostNameLength())
					.toLowerCase();
			} while (hosts.containsKey(name));
			return createHostAndAssignPort(name);
		} finally {
			readWriteLock.writeLock().unlock();
		}
	}

	public Host createHost(String name) {
		readWriteLock.writeLock().lock();
		try {
			if (hosts.containsKey(name)) {
				return null;
			}
			return createHostAndAssignPort(name);
		} finally {
			readWriteLock.writeLock().unlock();
		}
	}

	private Host createHostAndAssignPort(String name) {
		int port;
		do {
			port = (int)(Math.random() * (settings.getHostPortRangeEnd() -
					settings.getHostPortRangeStart()) + settings.getHostPortRangeStart());
		} while (ports.contains(port));
		Host host = new Host(name, port, TimeUnit.MILLISECONDS.convert(settings.getHostTimeout(),
				TimeUnit.SECONDS));
		ports.add(port);
		hosts.put(name, host);
		return host;
	}

	public void removeHost(Host host) {
		readWriteLock.writeLock().lock();
		try {
			hosts.remove(host.getName());
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
