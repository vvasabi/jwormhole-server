package com.bradchen.jwormhole.server;

import java.io.Serializable;

public final class Host implements Serializable {

	private static final long serialVersionUID = 3771827707514422212L;

	private final String key;
	private final int port;
	private final long createTime;
	private final long timeout;
	private long expiry;

	public Host(String key, int port, long timeout) {
		this.key = key;
		this.port = port;
		this.createTime = System.currentTimeMillis();
		this.timeout = timeout;
		expiry = createTime + timeout;
	}

	public String getKey() {
		return key;
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

	public synchronized boolean renew() {
		long now = System.currentTimeMillis();
		if (now > expiry) {
			return false;
		}
		expiry = now + timeout;
		return true;
	}

}
