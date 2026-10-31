package io.github.grahamfairbank.dsccmorpheus

import groovy.transform.CompileStatic

import java.io.IOException
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One HTTP exchange. An interface so DsccClient can be tested without a network. */
@CompileStatic
interface DsccTransport {
	HttpResult send(String method, String url, Map<String, String> headers, String body)
}

@CompileStatic
class HttpResult {
	final int status
	final String body
	final Map<String, List<String>> headers

	HttpResult(int status, String body, Map<String, List<String>> headers = [:]) {
		this.status = status
		this.body = body ?: ''
		this.headers = headers != null ? headers : new LinkedHashMap<String, List<String>>()
	}

	String header(String name) {
		Map.Entry<String, List<String>> e = headers.find { k, v -> k?.equalsIgnoreCase(name) }
		return e?.value ? e.value[0] : null
	}
}

/** A response body over JdkDsccTransport.MAX_BODY_BYTES; the rest is not read. */
@CompileStatic
class ResponseTooLargeException extends IOException {
	ResponseTooLargeException(long limit) { super("response larger than ${limit.intdiv(1024L * 1024L)} MiB".toString()) }
}

/** Real transport on the JDK HTTP client, with optional proxy, fixed timeouts and a response size limit. */
@CompileStatic
class JdkDsccTransport implements DsccTransport {

	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10)
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60)
	/** One page of 500 volumes is well under 2 MiB; anything near this is not a normal DSCC answer. */
	static final long MAX_BODY_BYTES = 32L * 1024L * 1024L

	private final HttpClient client

	JdkDsccTransport(String proxyUrl = null) {
		HttpClient.Builder b = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NEVER)
		if (proxyUrl) {
			URI p = new URI(proxyUrl)
			int port = p.port > 0 ? p.port : (p.scheme == 'https' ? 443 : 80)
			b.proxy(ProxySelector.of(new InetSocketAddress(p.host, port)))
		}
		client = b.build()
	}

	@Override
	HttpResult send(String method, String url, Map<String, String> headers, String body) {
		HttpRequest.Builder rb = HttpRequest.newBuilder(new URI(url)).timeout(REQUEST_TIMEOUT)
		headers?.each { k, v -> if (v != null) rb.header(k, v) }
		HttpRequest.BodyPublisher pub = body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody()
		rb.method(method, pub)
		HttpResponse<InputStream> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream())
		String text
		InputStream stream = r.body()
		try {
			text = new String(readLimited(stream, MAX_BODY_BYTES), 'UTF-8')
		} finally {
			stream.close()
		}
		return new HttpResult(r.statusCode(), text, r.headers().map())
	}

	/** Reads the whole stream, or throws ResponseTooLargeException once it passes limit bytes. */
	static byte[] readLimited(InputStream stream, long limit) {
		ByteArrayOutputStream out = new ByteArrayOutputStream()
		byte[] buf = new byte[65536]
		long total = 0L
		int n
		while ((n = stream.read(buf)) != -1) {
			total += n
			if (total > limit) throw new ResponseTooLargeException(limit)
			out.write(buf, 0, n)
		}
		return out.toByteArray()
	}
}
