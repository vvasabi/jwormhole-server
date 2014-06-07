package com.bradchen.jwormhole.server;

import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Stores all settings from properties files.
 */
public final class Settings {

	private static final String SETTING_PREFIX = "jwormhole.server.";
	private static final Pattern PATTERN = Pattern.compile("^[-_.a-z0-9]*$",
		Pattern.CASE_INSENSITIVE);

	private final String domainNamePrefix;
	private final String domainNameSuffix;
	private final int controllerPort;
	private final int hostPortRangeStart;
	private final int hostPortRangeEnd;
	private final int hostNameLength;
	private final int hostTimeout;
	private final int hostManagerGcInterval;
	private final boolean ipForwarded;
	private final boolean urlFragmentSent;

	public Settings(Properties defaults, Properties overrides) {
		domainNamePrefix = getSetting(defaults, overrides, "domainNamePrefix");
		domainNameSuffix = getSetting(defaults, overrides, "domainNameSuffix");
		controllerPort = getSettingInteger(defaults, overrides, "controllerPort");
		hostPortRangeStart = getSettingInteger(defaults, overrides, "hostPortRangeStart");
		hostPortRangeEnd = getSettingInteger(defaults, overrides, "hostPortRangeEnd");
		hostNameLength = getSettingInteger(defaults, overrides, "hostNameLength");
		hostTimeout = getSettingInteger(defaults, overrides, "hostTimeout");
		hostManagerGcInterval = getSettingInteger(defaults, overrides, "hostManagerGcInterval");
		ipForwarded = getSettingBoolean(defaults, overrides, "ipForwarded");
		urlFragmentSent = getSettingBoolean(defaults, overrides, "urlFragmentSent");
		validateSettings();
	}

	private void validateSettings() {
		if (!PATTERN.matcher(domainNamePrefix).matches()) {
			throw new RuntimeException("Invalid url prefix.");
		}
		if ((controllerPort <= 0) ||
				((controllerPort >= hostPortRangeStart) && (controllerPort <= hostPortRangeEnd))) {
			throw new RuntimeException("Invalid controller port.");
		}
		if ((hostPortRangeStart <= 0) || (hostPortRangeEnd <= 0) ||
				(hostPortRangeStart >= hostPortRangeEnd)) {
			throw new RuntimeException("Invalid host port range.");
		}
		if (hostNameLength <= 0) {
			throw new RuntimeException("Invalid host name length.");
		}
		if (hostTimeout <= 0) {
			throw new RuntimeException("Invalid host timeout.");
		}
		if ((hostManagerGcInterval <= 0) || (hostManagerGcInterval > hostTimeout)) {
			throw new RuntimeException("Invalid host manager GC period.");
		}
	}

	private static int getSettingInteger(Properties defaults, Properties overrides,
										  String key) {
		return Integer.parseInt(getSetting(defaults, overrides, key));
	}

	private static boolean getSettingBoolean(Properties defaults, Properties overrides,
											 String key) {
		return Boolean.parseBoolean(getSetting(defaults, overrides, key));
	}

	private static String getSetting(Properties defaults, Properties overrides, String key) {
		String fullKey = SETTING_PREFIX + key;
		if ((overrides != null) && overrides.containsKey(fullKey)) {
			return (String)overrides.get(fullKey);
		}
		return (String)defaults.get(fullKey);
	}

	public String getDomainNamePrefix() {
		return domainNamePrefix;
	}

	public String getDomainNameSuffix() {
		return domainNameSuffix;
	}

	public int getControllerPort() {
		return controllerPort;
	}

	public int getHostPortRangeStart() {
		return hostPortRangeStart;
	}

	public int getHostPortRangeEnd() {
		return hostPortRangeEnd;
	}

	public int getHostNameLength() {
		return hostNameLength;
	}

	public int getHostTimeout() {
		return hostTimeout;
	}

	public int getHostManagerGcInterval() {
		return hostManagerGcInterval;
	}

	public boolean isIpForwarded() {
		return ipForwarded;
	}

	public boolean isUrlFragmentSent() {
		return urlFragmentSent;
	}

}
