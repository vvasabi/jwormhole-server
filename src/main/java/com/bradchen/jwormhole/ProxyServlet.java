package com.bradchen.jwormhole;

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
import java.io.IOException;
import java.io.OutputStream;

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

	// A boolean parameter name to enable forwarding of the client IP
	private static final String P_FORWARDED_FOR = "forwardip";

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
	private ControlConsole controlConsole;

	@Override
	public void init(ServletConfig servletConfig) throws ServletException {
		try {
			hostManager = new HostManager();
			boolean doForwardIP = getBooleanServletConfig(servletConfig, P_FORWARDED_FOR, false);
			proxyRequestHandler = new ProxyRequestHandler(hostManager, doForwardIP, true);
			controlConsole = new ControlConsole(hostManager);
			controlConsole.run();
		} catch (IOException exception) {
			throw new ServletException(exception);
		}
	}

	private static boolean getBooleanServletConfig(ServletConfig servletConfig, String name,
												   boolean defaultValue) {
		String param = servletConfig.getInitParameter(name);
		return (param == null) ? defaultValue : Boolean.parseBoolean(param);
	}

	@Override
	public void destroy() {
		proxyRequestHandler.shutdown();
		hostManager.shutdown();
		controlConsole.shutdown();
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
