package com.bradchen.jwormhole;

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

public class ControlConsole {

	private static final Logger LOGGER = LoggerFactory.getLogger(ControlConsole.class);
	private static final int CONTROL_CONSOLE_PORT = 12700;

	private final HostManager hostManager;
	private final ServerSocket serverSocket;
	private boolean running;

	public ControlConsole(HostManager hostManager) throws IOException {
		this.hostManager = hostManager;
		running = true;
		serverSocket = new ServerSocket(CONTROL_CONSOLE_PORT);
	}

	public void run() {
		new Thread(() -> {
			while (running) {
				Socket socket = null;
				try {
					socket = serverSocket.accept();
					BufferedReader reader = new BufferedReader(new InputStreamReader(socket
						.getInputStream()));
					String response = processCommand(reader.readLine());
					if (response != null) {
						PrintWriter writer = new PrintWriter(socket.getOutputStream());
						writer.write(response + "\n");
						writer.flush();
					}
				} catch (SocketException exception) {
					// NOOP
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

		if ("createHost".equals(command)) {
			Host host = hostManager.createHost();
			return String.format("%s,%d", host.getKey(), host.getPort());
		}

		String[] tokens = command.split(" ");
		if ("renewHost".equals(tokens[0]) && (tokens.length == 2)) {
			Host host = hostManager.getHost(tokens[1]);
			if (host == null) {
				return "Invalid host: " + tokens[1];
			}

			host.renew();
			return null;
		}
		return invalidCommandResponse(command);
	}

	private String invalidCommandResponse(String command) {
		return "Invalid command: " + command;
	}

	public void shutdown() {
		try {
			running = false;
			serverSocket.close();
		} catch (IOException exception) {
			// NOOP
		}
	}

}
