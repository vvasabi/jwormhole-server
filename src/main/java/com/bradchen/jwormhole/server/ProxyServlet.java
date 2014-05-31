package com.bradchen.jwormhole.server;

/**
 * This class was modified from https://github.com/mitre/HTTP-Proxy-Servlet. Below is the original
 * license statement:
 *
 * Copyright MITRE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.HeaderGroup;
import org.apache.http.util.EntityUtils;

import javax.servlet.GenericServlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * An HTTP reverse proxy/gateway servlet. It is designed to be extended for customization
 * if desired. Most of the work is handled by
 * <a href="http://hc.apache.org/httpcomponents-client-ga/">Apache HttpClient</a>.
 * <p>
 *   There are alternatives to a servlet based proxy such as Apache mod_proxy if that is available to you. However
 *   this servlet is easily customizable by Java, secure-able by your web application's security (e.g. spring-security),
 *   portable across servlet engines, and is embeddable into another web application.
 * </p>
 * <p>
 *   Inspiration: http://httpd.apache.org/docs/2.0/mod/mod_proxy.html
 * </p>
 *
 * @author David Smiley dsmiley@mitre.org
 */
@SuppressWarnings("serial")
public final class ProxyServlet extends GenericServlet {

	// in class path
	private static final String DEFAULT_SETTINGS_FILE = "settings.default.properties";

	// relative to $HOME
	private static final String OVERRIDE_SETTINGS_FILE = ".jwormhole/server.properties";

	/**
	 * These are the "hop-by-hop" headers that should not be copied.
	 * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html
	 * I use an HttpClient HeaderGroup class instead of Set<String> because this
	 * approach does case insensitive lookup faster.
	 */
	static final HeaderGroup HOP_BY_HOP_HEADERS;

	static {
		HOP_BY_HOP_HEADERS = new HeaderGroup();
		String[] headers = new String[] {
			"Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization",
			"TE", "Trailers", "Transfer-Encoding", "Upgrade" };
		for (String header : headers) {
			HOP_BY_HOP_HEADERS.addHeader(new BasicHeader(header, null));
		}
	}

	private HostManager hostManager;
	private ProxyRequestHandler proxyRequestHandler;
	private Controller controller;

	@Override
	public void init(ServletConfig servletConfig) throws ServletException {
		try {
			Settings settings = new Settings(readDefaultSettings(), readOverrideSettings());
			hostManager = new HostManager(settings);
			proxyRequestHandler = new ProxyRequestHandler(settings, hostManager);
			controller = new Controller(settings, hostManager);
			controller.run();
		} catch (IOException exception) {
			throw new ServletException(exception);
		}
	}

	private static Properties readDefaultSettings() throws IOException {
		ClassLoader tcl = Thread.currentThread().getContextClassLoader();
		InputStream inputStream = null;
		try {
			inputStream = tcl.getResourceAsStream(DEFAULT_SETTINGS_FILE);
			return readPropertiesFile(inputStream);
		} finally {
			IOUtils.closeQuietly(inputStream);
		}
	}

	private static Properties readOverrideSettings() throws IOException {
		File file = new File(System.getenv("HOME") + "/" + OVERRIDE_SETTINGS_FILE);
		if (!file.exists()) {
			return null;
		}

		InputStream inputStream = null;
		try {
			inputStream = new FileInputStream(file);
			return readPropertiesFile(inputStream);
		} finally {
			IOUtils.closeQuietly(inputStream);
		}
	}

	private static Properties readPropertiesFile(InputStream inputStream) throws IOException {
		Properties properties = new Properties();
		properties.load(inputStream);
		return properties;
	}

	@Override
	public void destroy() {
		proxyRequestHandler.shutdown();
		hostManager.shutdown();
		controller.shutdown();
	}

	@Override
	public void service(ServletRequest req, ServletResponse res)
			throws ServletException, IOException {
		HttpServletRequest servletRequest = (HttpServletRequest)req;
		HttpServletResponse servletResponse = (HttpServletResponse)res;
		HttpResponse proxyResponse = null;
		String targetUri;
		try {
			proxyResponse = proxyRequestHandler.handle(servletRequest);
			targetUri = proxyRequestHandler.getTargetUri(servletRequest);
			if (proxyResponse == null) {
				servletResponse.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}

			// Process the response
			int statusCode = proxyResponse.getStatusLine().getStatusCode();
			if (doResponseRedirectOrNotModifiedLogic(servletRequest, servletResponse, proxyResponse,
					statusCode, targetUri)) {
				// the response is already "committed" now without any body to send
				return;
			}

			// Pass the response code. This method with the "reason phrase" is deprecated but it's the only way to pass the
			//  reason along too.
			//noinspection deprecation
			servletResponse.setStatus(statusCode, proxyResponse.getStatusLine().getReasonPhrase());
			copyResponseHeaders(proxyResponse, servletResponse);

			// Send the content to the client
			copyResponseEntity(proxyResponse, servletResponse);
		} finally {
			// make sure the entire entity was consumed, so the connection is released
			if (proxyResponse != null) {
				consumeQuietly(proxyResponse.getEntity());
			}
			IOUtils.closeQuietly(servletResponse.getOutputStream());
		}
	}

	private boolean doResponseRedirectOrNotModifiedLogic(HttpServletRequest servletRequest,
														   HttpServletResponse servletResponse,
														   HttpResponse proxyResponse,
														   int statusCode, String targetUri)
			throws ServletException, IOException {
		// Check if the proxy response is a redirect
		// The following code is adapted from org.tigris.noodle.filters.CheckForRedirect
		if ((statusCode >= HttpServletResponse.SC_MULTIPLE_CHOICES) /* 300 */
				&& (statusCode < HttpServletResponse.SC_NOT_MODIFIED) /* 304 */) {
			Header locationHeader = proxyResponse.getLastHeader(HttpHeaders.LOCATION);
			if (locationHeader == null) {
				throw new ServletException("Received status code: " + statusCode
					+ " but no " + HttpHeaders.LOCATION + " header was found in the response");
			}
			// Modify the redirect to go to this proxy servlet rather that the proxied host
			String locStr = rewriteUrlFromResponse(servletRequest, targetUri,
				locationHeader.getValue());

			servletResponse.sendRedirect(locStr);
			return true;
		}

		// 304 needs special handling.  See:
		// http://www.ics.uci.edu/pub/ietf/http/rfc1945.html#Code304
		// We get a 304 whenever passed an 'If-Modified-Since'
		// header and the data on disk has not changed; server
		// responds w/ a 304 saying I'm not going to send the
		// body because the file has not changed.
		if (statusCode == HttpServletResponse.SC_NOT_MODIFIED) {
			servletResponse.setIntHeader(HttpHeaders.CONTENT_LENGTH, 0);
			servletResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			return true;
		}
		return false;
	}

	private void consumeQuietly(HttpEntity entity) {
		try {
			EntityUtils.consume(entity);
		} catch (IOException exception) {
			// NOOP
		}
	}

	/**
	 * Copy proxied response headers back to the servlet client.
	 */
	private void copyResponseHeaders(HttpResponse proxyResponse,
									 HttpServletResponse servletResponse) {
		for (Header header : proxyResponse.getAllHeaders()) {
			if (HOP_BY_HOP_HEADERS.containsHeader(header.getName())) {
				continue;
			}
			servletResponse.addHeader(header.getName(), header.getValue());
		}
	}

	/**
	 * Copy response body data (the entity) from the proxy to the servlet client.
	 */
	private void copyResponseEntity(HttpResponse proxyResponse, HttpServletResponse servletResponse)
			throws IOException {
		HttpEntity entity = proxyResponse.getEntity();
		if (entity != null) {
			OutputStream servletOutputStream = servletResponse.getOutputStream();
			entity.writeTo(servletOutputStream);
		}
	}

	/**
	 * For a redirect response from the target server, this translates {@code theUrl} to redirect to
	 * and translates it to one the original client can use.
	 */
	private String rewriteUrlFromResponse(HttpServletRequest servletRequest, String targetUri,
										  String theUrl) {
		if ((targetUri == null) || !theUrl.startsWith(targetUri)) {
			return theUrl;
		}

		String curUrl = servletRequest.getRequestURL().toString(); //no query
		String pathInfo = servletRequest.getPathInfo();
		if (pathInfo != null) {
			assert curUrl.endsWith(pathInfo);
			curUrl = curUrl.substring(0,curUrl.length()-pathInfo.length()); //take pathInfo off
		}
		return curUrl + theUrl.substring(targetUri.length());
	}

}
