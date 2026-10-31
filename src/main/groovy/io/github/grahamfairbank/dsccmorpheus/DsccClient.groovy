package io.github.grahamfairbank.dsccmorpheus

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/** A DSCC call that failed. The message is safe to show to users (no secrets, short). */
class DsccException extends RuntimeException {
	final int status

	DsccException(String message, int status = 0) {
		super(message)
		this.status = status
	}

	boolean isNotFound() { status == 404 }
}

/** An index build ran past DsccService.INDEX_BUILD_BUDGET_MS. Stops the whole build, not one system. */
class DsccBudgetException extends DsccException {
	DsccBudgetException(String message) { super(message) }
}

/**
 * Read-only HPE GreenLake Data Services (DSCC) storage API client. GET only.
 *
 * - Signs in with OAuth2 client credentials (DsccSettings.tokenUrls: workspace issuer first). The URL that worked
 *   is tried first next time. Tokens are reused until a minute before expiry (workspace tokens last 15 minutes).
 * - Every list call sends both limit and offset: DSCC answers HTTP 400 otherwise (measured 2026-10-29).
 * - GET responses are cached per path for the TTL the caller gives. 401 drops the token and retries once.
 */
@Slf4j
class DsccClient {

	static final String BASE = '/api/v1'
	static final int PAGE = 500
	static final int MAX_PAGES = 200
	/** device-type1: 3PAR, Primera, Alletra 9000. device-type2: Nimble, Alletra 5000/6000. device-type4: Alletra MP B10000. */
	static final List<Integer> FAMILIES = [1, 2, 4]

	final DsccSettings settings
	private final DsccTransport transport
	private final Closure<Long> clock

	/** While an index build runs: the time (clock ms) after which DSCC calls stop. 0 = no limit. */
	volatile long deadline = 0L

	private String token
	private long tokenExpiresAt
	private String workingTokenUrl
	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>()

	private static class CacheEntry {
		final long at
		final Object data
		CacheEntry(long at, Object data) { this.at = at; this.data = data }
	}

	DsccClient(DsccSettings settings, DsccTransport transport, Closure<Long> clock = { System.currentTimeMillis() }) {
		this.settings = settings
		this.transport = transport
		this.clock = clock
	}

	// ---- endpoints (all read-only) -------------------------------------------------------------

	List<Map> systems(int family, long ttlMs) { listAll("${BASE}/storage-systems/device-type${family}", ttlMs) }

	Map system(int family, String id, long ttlMs) { (Map) getJson("${BASE}/storage-systems/device-type${family}/${enc(id)}", ttlMs) }

	/** device-type1 and device-type4 only. Values in MiB. */
	Map capacitySummary(int family, String id, long ttlMs) {
		(Map) getJson("${BASE}/storage-systems/device-type${family}/${enc(id)}/capacity-summary", ttlMs)
	}

	/** device-type2 only. */
	List<Map> healthStatus(String id, long ttlMs) { listAll("${BASE}/storage-systems/device-type2/${enc(id)}/health-status", ttlMs) }

	List<Map> volumes(int family, String id, long ttlMs) { listAll("${BASE}/storage-systems/device-type${family}/${enc(id)}/volumes", ttlMs) }

	// ---- plumbing -------------------------------------------------------------------------------

	void clearCache() { cache.clear() }

	private final ConcurrentHashMap<String, Object> listLocks = new ConcurrentHashMap<>()

	/** Paged GET of a collection: follows offset until total is reached (or MAX_PAGES as a safety stop). */
	List<Map> listAll(String path, long ttlMs, int pageSize = PAGE) {
		if (ttlMs <= 0) return listPages(path, ttlMs, pageSize)
		Object lock = listLocks.computeIfAbsent(path) { new Object() }
		synchronized (lock) { return listPages(path, ttlMs, pageSize) }   // a waiter then finds the pages cached
	}

	private List<Map> listPages(String path, long ttlMs, int pageSize) {
		List<Map> all = []
		int offset = 0
		for (int page = 0; page < MAX_PAGES; page++) {
			String sep = path.contains('?') ? '&' : '?'
			Map body = (Map) getJson("${path}${sep}limit=${pageSize}&offset=${offset}", ttlMs)
			List items = (List) (body?.items ?: [])
			all.addAll(items as List<Map>)
			Integer total = body?.total as Integer
			offset += items.size()
			if (items.isEmpty() || (total != null && offset >= total)) break
		}
		return all
	}

	Object getJson(String pathAndQuery, long ttlMs) {
		long now = clock.call()
		CacheEntry hit = cache[pathAndQuery]
		if (hit && ttlMs > 0 && now - hit.at < ttlMs) return hit.data

		if (deadline > 0L && now > deadline) {
			throw new DsccBudgetException('Reading DSCC took longer than ' +
				"${DsccService.INDEX_BUILD_BUDGET_MS.intdiv(60_000L)} minutes, so the plugin stopped this read.")
		}
		HttpResult r = get(pathAndQuery)
		if (r.status == 401) {
			synchronized (this) { token = null }
			r = get(pathAndQuery)
		}
		if (r.status != 200) throw failure(r, "DSCC ${shortPath(pathAndQuery)}")
		Object data
		try {
			data = new JsonSlurper().parseText(r.body)
		} catch (Exception e) {
			throw new DsccException("DSCC ${shortPath(pathAndQuery)} returned something that isn't JSON", r.status)
		}
		if (ttlMs > 0) cache[pathAndQuery] = new CacheEntry(now, data)
		return data
	}

	private HttpResult get(String pathAndQuery) {
		Map<String, String> h = ['Authorization': "Bearer ${accessToken()}".toString(), 'Accept': 'application/json']
		try {
			return transport.send('GET', settings.apiUrl + pathAndQuery, h, null)
		} catch (ResponseTooLargeException e) {
			log.warn("dscc-morpheus: DSCC ${shortPath(pathAndQuery)}: ${e.message}, not read")
			throw new DsccException("DSCC ${shortPath(pathAndQuery)} sent a ${e.message}, which the plugin doesn't read. Details are in the Morpheus log.")
		} catch (Exception e) {
			log.warn("dscc-morpheus: request to ${settings.apiUrl} failed: ${e}")
			throw new DsccException("Couldn't reach DSCC at ${settings.apiUrl} (${e.class.simpleName}). Details are in the Morpheus log.")
		}
	}

	/** A valid access token, signing in if needed. */
	synchronized String accessToken() {
		long now = clock.call()
		if (token && now < tokenExpiresAt - 60_000L) return token
		if (!settings.configured) throw new DsccException('The plugin has no DSCC API client ID and secret yet')

		List<String> urls = settings.tokenUrls()
		if (workingTokenUrl && urls.contains(workingTokenUrl)) urls = [workingTokenUrl] + (urls - workingTokenUrl)
		List<String> tried = []
		for (String url : urls) {
			HttpResult r
			try {
				r = transport.send('POST', url, ['Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json'],
					"grant_type=client_credentials&client_id=${enc(settings.clientId)}&client_secret=${enc(settings.clientSecret)}".toString())
			} catch (Exception e) {
				tried << "${hostOf(url)}: ${e.class.simpleName}".toString()
				continue
			}
			if (r.status == 200) {
				Map body = null
				try { body = (Map) new JsonSlurper().parseText(r.body) } catch (Exception ignored) { }
				if (body?.access_token) {
					token = body.access_token as String
					long life = ((body.expires_in ?: 900) as long) * 1000L
					tokenExpiresAt = now + life
					if (workingTokenUrl != url) log.info("dscc-morpheus: signed in to GreenLake via ${hostOf(url)}")
					workingTokenUrl = url
					return token
				}
			}
			tried << "${hostOf(url)}: HTTP ${r.status}".toString()
		}
		throw new DsccException("Couldn't sign in to GreenLake with the configured client (${tried.join('; ')}). " +
			'Check the client ID, secret and workspace ID.')
	}

	private DsccException failure(HttpResult r, String what) {
		String body = r.body ?: ''
		if (body.contains('ERROR_FAILED_TO_AUTHORIZE')) {
			// The token is valid but carries no Data Services access: usually a legacy (workspace-less) token.
			// Drop it, and stop preferring legacy SSO, so the next sign-in tries the workspace issuers first again.
			synchronized (this) { if (workingTokenUrl == DsccSettings.LEGACY_TOKEN_URL) { token = null; workingTokenUrl = null } }
			return new DsccException("${what}: DSCC authorized no access for this API client. Set the GreenLake workspace ID " +
				'(the plugin then signs in through the workspace), and check that the client\'s user has a Data Services role in that workspace.', r.status)
		}
		if (r.status == 403) return new DsccException("${what}: the DSCC API client doesn't have permission (HTTP 403)", 403)
		if (r.status == 404) return new DsccException("${what}: not found in DSCC (HTTP 404)", 404)
		if (r.status == 429) return new DsccException("${what}: rate limited by DSCC (HTTP 429), try again shortly", 429)
		// DSCC's response body can hold internal detail: log it, don't return it.
		log.warn("dscc-morpheus: ${what}: HTTP ${r.status} ${body.replaceAll('\\s+', ' ').take(500)}")
		return new DsccException("${what}: HTTP ${r.status}. Details are in the Morpheus log.", r.status)
	}

	private static String shortPath(String p) { p.replaceFirst('^/api/v1', '').replaceFirst('\\?.*$', '') }

	private static String hostOf(String url) {
		try { return new URI(url).host } catch (Exception ignored) { return url }
	}

	/** Percent-encodes a path segment or query value. */
	static String enc(String s) { URLEncoder.encode(s ?: '', 'UTF-8').replace('+', '%20') }
}
