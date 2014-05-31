package com.bradchen.jwormhole.server;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.AbortableHttpRequest;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.Formatter;

public class ProxyRequestHandler {

	private static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";
	private static final BitSet ASCII_QUERY_CHARS;

	static {
		char[] c_unreserved = "_-!.~'()*".toCharArray(); //plus alphanum
		char[] c_punct = ",;:$&+=".toCharArray();
		char[] c_reserved = "?/[]@".toCharArray(); //plus punct

		ASCII_QUERY_CHARS = new BitSet(128);
		for(char c = 'a'; c <= 'z'; c++) ASCII_QUERY_CHARS.set((int)c);
		for(char c = 'A'; c <= 'Z'; c++) ASCII_QUERY_CHARS.set((int)c);
		for(char c = '0'; c <= '9'; c++) ASCII_QUERY_CHARS.set((int)c);
		for(char c : c_unreserved) ASCII_QUERY_CHARS.set((int)c);
		for(char c : c_punct) ASCII_QUERY_CHARS.set((int)c);
		for(char c : c_reserved) ASCII_QUERY_CHARS.set((int)c);

		ASCII_QUERY_CHARS.set((int) '%'); //leave existing percent escapes in place
	}

	private final Settings settings;
	private final HostManager hostManager;
	private final CloseableHttpClient proxyClient;

	public ProxyRequestHandler(Settings settings, HostManager hostManager) {
		this.hostManager = hostManager;
		this.settings = settings;
		this.proxyClient = HttpClients.createDefault();
	}

	public String getTargetUri(HttpServletRequest servletRequest) {
		Host host = hostManager.getHost(servletRequest.getHeader(HttpHeaders.HOST));
		return (host == null) ? null : "http://localhost:" + host.getPort() + "/";
	}

	public HttpResponse handle(HttpServletRequest servletRequest) throws IOException {
		HttpRequest proxyRequest = null;
		try {
			// Make the Request
			//note: we won't transfer the protocol version because I'm not sure it would truly be compatible
			String targetUriString = getTargetUri(servletRequest);
			if (targetUriString == null) {
				return null;
			}

			URI targetUri = new URI(targetUriString);
			String method = servletRequest.getMethod();
			String proxyRequestUri = rewriteUrlFromRequest(targetUri, servletRequest);

			//spec: RFC 2616, sec 4.3: either of these two headers signal that there is a message body.
			if (servletRequest.getHeader(HttpHeaders.CONTENT_LENGTH) != null ||
					servletRequest.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
				HttpEntityEnclosingRequest eProxyRequest = new BasicHttpEntityEnclosingRequest(method, proxyRequestUri);
				// Add the input entity (streamed)
				//  note: we don't bother ensuring we close the servletInputStream since the container handles it
				eProxyRequest.setEntity(new InputStreamEntity(servletRequest.getInputStream(), servletRequest.getContentLength()));
				proxyRequest = eProxyRequest;
			} else {
				proxyRequest = new BasicHttpRequest(method, proxyRequestUri);
			}

			copyRequestHeaders(targetUri, servletRequest, proxyRequest);
			setXForwardedForHeader(servletRequest, proxyRequest);
			return proxyClient.execute(URIUtils.extractHost(targetUri), proxyRequest);
		} catch (URISyntaxException exception) {
			// NOOP
			return null;
		} catch (IOException exception) {
			if (proxyRequest instanceof AbortableHttpRequest) {
				AbortableHttpRequest abortableHttpRequest = (AbortableHttpRequest) proxyRequest;
				abortableHttpRequest.abort();
			}
			throw exception;
		}
	}

	public void shutdown() {
		IOUtils.closeQuietly(proxyClient);
	}

	/**
	 * Reads the request URI from {@code servletRequest} and rewrites it. It's used to make the new request.
	 */
	protected String rewriteUrlFromRequest(URI targetUri, HttpServletRequest servletRequest) {
		StringBuilder uri = new StringBuilder(500);
		uri.append(targetUri);
		// Handle the path given to the servlet
		if (servletRequest.getPathInfo() != null) {//ex: /my/path.html
			uri.append(encodeUriQuery(servletRequest.getPathInfo()));
		}
		// Handle the query string
		String queryString = servletRequest.getQueryString();//ex:(following '?'): name=value&foo=bar#fragment
		if (queryString != null && queryString.length() > 0) {
			uri.append('?');
			int fragIdx = queryString.indexOf('#');
			String queryNoFrag = (fragIdx < 0 ? queryString : queryString.substring(0,fragIdx));
			uri.append(encodeUriQuery(queryNoFrag));
			if (settings.isUrlFragmentSent() && fragIdx >= 0) {
				uri.append('#');
				uri.append(encodeUriQuery(queryString.substring(fragIdx + 1)));
			}
		}
		return uri.toString();
	}

	private void setXForwardedForHeader(HttpServletRequest servletRequest,
										HttpRequest proxyRequest) {
		if (!settings.isIpForwarded()) {
			return;
		}

		String newHeader = servletRequest.getRemoteAddr();
		String existingHeader = servletRequest.getHeader(X_FORWARDED_FOR_HEADER);
		if (existingHeader != null) {
			newHeader = existingHeader + ", " + newHeader;
		}
		proxyRequest.setHeader(X_FORWARDED_FOR_HEADER, newHeader);
	}

	/** Copy request headers from the servlet client to the proxy request. */
	private void copyRequestHeaders(URI targetUri, HttpServletRequest servletRequest,
									HttpRequest proxyRequest) {
		// Get an Enumeration of all of the header names sent by the client
		Enumeration<String> enumerationOfHeaderNames = servletRequest.getHeaderNames();
		while (enumerationOfHeaderNames.hasMoreElements()) {
			String headerName = enumerationOfHeaderNames.nextElement();
			//Instead the content-length is effectively set via InputStreamEntity
			if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH))
				continue;
			if (ProxyServlet.HOP_BY_HOP_HEADERS.containsHeader(headerName))
				continue;

			Enumeration<String> headers = servletRequest.getHeaders(headerName);
			while (headers.hasMoreElements()) {//sometimes more than one value
				String headerValue = headers.nextElement();
				// In case the proxy host is running multiple virtual servers,
				// rewrite the Host header to ensure that we get content from
				// the correct virtual server
				if (headerName.equalsIgnoreCase(HttpHeaders.HOST)) {
					HttpHost host = URIUtils.extractHost(targetUri);
					headerValue = host.getHostName();
					if (host.getPort() != -1)
						headerValue += ":"+host.getPort();
				}
				proxyRequest.addHeader(headerName, headerValue);
			}
		}
	}

	/**
	 * Encodes characters in the query or fragment part of the URI.
	 *
	 * <p>Unfortunately, an incoming URI sometimes has characters disallowed by the spec.  HttpClient
	 * insists that the outgoing proxied request has a valid URI because it uses Java's {@link URI}.
	 * To be more forgiving, we must escape the problematic characters.  See the URI class for the
	 * spec.
	 *
	 * @param in example: name=value&foo=bar#fragment
	 */
	private static CharSequence encodeUriQuery(CharSequence in) {
		//Note that I can't simply use URI.java to encode because it will escape pre-existing escaped things.
		StringBuilder outBuf = null;
		Formatter formatter = null;
		for(int i = 0; i < in.length(); i++) {
			char c = in.charAt(i);
			boolean escape = true;
			if (c < 128) {
				if (ASCII_QUERY_CHARS.get((int)c)) {
					escape = false;
				}
			} else if (!Character.isISOControl(c) && !Character.isSpaceChar(c)) {//not-ascii
				escape = false;
			}
			if (!escape) {
				if (outBuf != null)
					outBuf.append(c);
			} else {
				//escape
				if (outBuf == null) {
					outBuf = new StringBuilder(in.length() + 5*3);
					outBuf.append(in,0,i);
					formatter = new Formatter(outBuf);
				}
				//leading %, 0 padded, width 2, capital hex
				formatter.format("%%%02X",(int)c); //TODO
			}
		}
		return outBuf != null ? outBuf : in;
	}

}
