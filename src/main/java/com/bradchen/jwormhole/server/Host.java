package com.bradchen.jwormhole.server;

import java.io.Serializable;

/**
 * Represents a jWormhole client.
 */
public final class Host implements Serializable {

	private static final long serialVersionUID = 3771827707514422212L;

	private final String name;
	private final int port;
	private final long createTime;
	private final long timeout;
	private long expiry;

	public Host(String name, int port, long timeout) {
		this.name = name;
		this.port = port;
		this.createTime = System.currentTimeMillis();
		this.timeout = timeout;
		expiry = createTime + timeout;
	}

	public String getName() {
		return name;
	}

	public int getPort() {
		return port;
	}

	public long getCreateTime() {
		return createTime;
	}

	public long getExpiry() {
		return expiry;
	}

	public synchronized boolean isExpired() {
		return System.currentTimeMillis() > expiry;
	}

	public synchronized boolean keepAlive() {
		if (isExpired()) {
			return false;
		}
		expiry = System.currentTimeMillis() + timeout;
		return true;
	}

}
