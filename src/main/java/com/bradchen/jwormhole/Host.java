package com.bradchen.jwormhole;

import java.io.Serializable;

public final class Host implements Serializable {

	private static final long serialVersionUID = 3771827707514422212L;
	private static final long TIMEOUT = 60000; // 60 seconds

	private final String key;
	private final int port;
	private final long createTime;
	private long expiry;

	public Host(String key, int port) {
		this.key = key;
		this.port = port;
		createTime = System.currentTimeMillis();
		expiry = createTime + TIMEOUT;
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

	public synchronized boolean isExpired() {
		return System.currentTimeMillis() > expiry;
	}

	public synchronized boolean renew() {
		long now = System.currentTimeMillis();
		if (now > expiry) {
			return false;
		}
		expiry = now + TIMEOUT;
		return true;
	}

}
