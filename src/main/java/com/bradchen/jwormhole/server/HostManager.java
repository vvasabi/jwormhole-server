package com.bradchen.jwormhole.server;

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
		scheduler.scheduleAtFixedRate(this::removeExpiredHosts, settings.getHostManagerGcPeriod(),
			settings.getHostManagerGcPeriod(), TimeUnit.SECONDS);
	}

	public Host getHost(String domainName) {
		if (domainName == null) {
			return null;
		}

		readWriteLock.readLock().lock();
		try {
			String portRemoved = domainName.replaceAll(PORT_PATTERN, "");
			String key = getKeyFromDomainName(portRemoved.toLowerCase());
			if (key == null) {
				return null;
			}
			return hosts.get(key);
		} finally {
			readWriteLock.readLock().unlock();
		}
	}

	public String getKeyFromDomainName(String domainName) {
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
			String key;
			do {
				key = RandomStringUtils.randomAlphanumeric(settings.getHostKeyLength())
					.toLowerCase();
			} while (hosts.containsKey(key));
			int port;
			do {
				port = (int)(Math.random() * (settings.getHostPortRangeEnd() -
					settings.getHostPortRangeStart()) + settings.getHostPortRangeStart());
			} while (ports.contains(port));
			Host host = new Host(key, port, TimeUnit.MILLISECONDS.convert(settings.getHostTimeout(),
				TimeUnit.SECONDS));
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
