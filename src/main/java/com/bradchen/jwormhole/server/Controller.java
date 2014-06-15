package com.bradchen.jwormhole.server;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Used to control the server via a simple plain text socket protocol.
 */
public class Controller {

	private static final Logger LOGGER = LoggerFactory.getLogger(Controller.class);
	private static final DateFormat FULL_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private static final Pattern HOST_NAME_PATTERN = Pattern.compile("^[-_.a-z0-9]+$",
		Pattern.CASE_INSENSITIVE);
	private static final String OK = "ok";

	private final Settings settings;
	private final HostManager hostManager;
	private final ServerSocket serverSocket;
	private boolean running;

	public Controller(Settings settings, HostManager hostManager) throws IOException {
		this.settings = settings;
		this.hostManager = hostManager;
		running = true;
		serverSocket = new ServerSocket(settings.getControllerPort());
	}

	public void run() {
		new Thread(() -> {
			while (running) {
				Socket socket = null;
				try {
					socket = serverSocket.accept();
					BufferedReader reader = new BufferedReader(new InputStreamReader(socket
						.getInputStream()));
					String response = processCommand(reader.readLine().trim());
					if (response != null) {
						PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
						writer.write(response + "\n");
						writer.close();
					}
				} catch (SocketException ignored) {
				} catch (IOException exception) {
					LOGGER.warn("Error occurred while processing request", exception);
				} finally {
					IOUtils.closeQuietly(socket);
				}
			}
		}).start();
	}

	private String processCommand(String command) {
		if (StringUtils.isBlank(command)) {
			return invalidCommandResponse(command);
		}

		if ("listHosts".equals(command)) {
			StringBuilder sb = new StringBuilder();
			Map<String, Host> hosts = hostManager.getHosts();
			for (Map.Entry<String, Host> entry : hosts.entrySet()) {
				Host host = entry.getValue();
				sb.append(getHostDomainName(host));
				sb.append(" ");
				sb.append(host.getPort());
				sb.append(" ");
				sb.append(FULL_DATE_FORMAT.format(new Date(host.getCreateTime())));
				sb.append(" ");
				sb.append(FULL_DATE_FORMAT.format(new Date(host.getExpiry())));
				sb.append("\n");
			}
			sb.append("# hosts: ");
			sb.append(hosts.size());
			return sb.toString();
		}

		String[] tokens = command.split(" ");
		if ("createHost".equals(tokens[0])) {
			Host host = null;
			if (tokens.length == 2) {
				if (HOST_NAME_PATTERN.matcher(tokens[1]).matches()) {
					host = hostManager.createHost(tokens[1]);
				}
			} else {
				host = hostManager.createHost();
			}
			if (host == null) {
				return "error";
			}
			return String.format("%s,%s,%d", getHostDomainName(host), host.getName(),
				host.getPort());
		}

		if ("keepHostAlive".equals(tokens[0]) && (tokens.length == 2)) {
			Host host = hostManager.getHost(tokens[1]);
			if (host == null) {
				return "Invalid host: " + tokens[1];
			}

			host.keepAlive();
			return OK;
		}

		if ("removeHost".equals(tokens[0]) && (tokens.length == 2)) {
			Host host = hostManager.getHost(tokens[1]);
			if (host == null) {
				return "Invalid host: " + tokens[1];
			}

			hostManager.removeHost(host);
			return OK;
		}
		return invalidCommandResponse(command);
	}

	private String getHostDomainName(Host host) {
		return String.format("%s%s%s", settings.getDomainNamePrefix(), host.getName(),
			settings.getDomainNameSuffix());
	}

	private String invalidCommandResponse(String command) {
		return "Invalid command: " + command;
	}

	public void shutdown() {
		try {
			running = false;
			serverSocket.close();
		} catch (IOException ignored) {
		}
	}

}
