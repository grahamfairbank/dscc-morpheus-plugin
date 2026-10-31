package io.github.grahamfairbank.dsccmorpheus

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** A DSCC call that failed. The message is safe to show to users (no secrets, short). */
class DsccException extends RuntimeException {
	final int status
	/** DSCC's errorCode (for example ERR_INVALID_KEY), for the plugin's own decisions; never shown. */
	final String errorCode

	DsccException(String message, int status = 0, String errorCode = null) {
		super(message)
		this.status = status
		this.errorCode = errorCode
	}

	boolean isNotFound() { status == 404 }
}

/** An index build ran past DsccService.INDEX_BUILD_BUDGET_MS. Stops the whole build, not one system. */
class DsccBudgetException extends DsccException {
	DsccBudgetException(String message) { super(message) }
}

/** DSCC answered HTTP 429. Stops the whole build (DsccIndex keeps nothing half-read); retryAfterMs is from its headers. */
class DsccRateLimitException extends DsccException {
	final Long retryAfterMs

	DsccRateLimitException(String message, Long retryAfterMs) {
		super(message, 429)
		this.retryAfterMs = retryAfterMs
	}
}

/** The client was replaced (settings changed) while its build ran: the rest of that build is skipped. */
class DsccStoppedException extends DsccBudgetException {
	DsccStoppedException() { super('The plugin settings changed, so this DSCC read stopped.') }
}

/**
 * Read-only HPE GreenLake Data Services (DSCC) storage API client. GET only.
 *
 * - Signs in with OAuth2 client credentials (DsccSettings.tokenUrls: workspace issuer first). The URL that worked
 *   is tried first next time. Tokens are reused until a minute before expiry (workspace tokens last 15 minutes).
 * - Every list call sends both limit and offset: DSCC answers HTTP 400 otherwise (measured 2026-10-29).
 * - GET responses are cached per path for the TTL the caller gives. 401 drops the token and retries once.
 * - Volume lists ask only for the fields the index reads (select). One unknown key makes DSCC refuse the whole
 *   request (HTTP 400 ERR_INVALID_KEY, measured 2026-10-31), so a family whose select is refused that way is read
 *   without it for the life of this client. Another 400 that only happens with select, or a selected page that
 *   looks wrong (no device ids, no states, or on device-type4 no sizes), is read again without select.
 */
@Slf4j
class DsccClient {

	static final String BASE = '/api/v1'
	static final int PAGE = 500
	static final int MAX_PAGES = 200
	/** device-type1: 3PAR, Primera, Alletra 9000. device-type2: Nimble, Alletra 5000/6000. device-type4: Alletra MP B10000. */
	static final List<Integer> FAMILIES = [1, 2, 4]

	/**
	 * Fields of each family's volume list that DsccIndex reads, checked against storage-api.json (2026-10-31).
	 * device-type4 has no top-level sizeMiB/usedSizeMiB: its sizes are in volumeCapacity. device-type1 has no
	 * volumeCapacity. Measured: select makes these reads about 8 to 10 times faster.
	 */
	static final Map<Integer, String> VOLUME_SELECT = [
		1: 'id,name,wwn,sizeMiB,usedSizeMiB,state,userCpgName,snapshotCpgName,consoleUri',
		4: 'id,name,wwn,volumeCapacity.sizeMiB,volumeCapacity.usedSizeMiB,state,userCpgName,usrCpgShortName,consoleUri',
		2: 'id,name,serial_number,size,total_usage_bytes,vol_usage_compressed_bytes,vol_state,online,offline_reason,space_usage_level,pool_name,consoleUri'
	].asImmutable()

	/** The longest wait a 429 can ask for before the next index read. */
	static final long MAX_RETRY_AFTER_MS = 15L * 60_000L
	private static final Pattern API_PREFIX = Pattern.compile('^/api/v1')
	private static final Pattern QUERY = Pattern.compile('\\?.*$')
	private static final Pattern SPACES = Pattern.compile('\\s+')

	final DsccSettings settings
	private final DsccTransport transport
	private final Closure<Long> clock

	/** While an index build runs: the time (clock ms) after which DSCC calls stop. 0 = no limit. */
	volatile long deadline = 0L
	/** Set when settings change: every later call of this client fails at once. */
	volatile boolean stopped = false
	/** Families whose volume list is read without select (DSCC refused it). */
	private final Set<Integer> noSelect = ConcurrentHashMap.newKeySet()
	/** Families whose select failed in another way: read without it until then (clock ms). */
	private final Map<Integer, Long> selectPausedUntil = new ConcurrentHashMap<>()
	/** How long a select failure other than ERR_INVALID_KEY turns select off for a family. */
	static final long SELECT_PAUSE_MS = 60L * 60_000L
	private static final Pattern ERROR_CODE = Pattern.compile('"errorCode"\\s*:\\s*"([A-Z0-9_]{1,64})"')

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

	/**
	 * A system's volumes, with select. Read again without select when DSCC answers a select with HTTP 400 that goes
	 * away without it, or when the selected page has items but none with a device id, none with a state, or (device-
	 * type4) none with a size, while the page without select has them.
	 */
	List<Map> volumes(int family, String id, long ttlMs) {
		String path = "${BASE}/storage-systems/device-type${family}/${enc(id)}/volumes".toString()
		String select = usesSelect(family) ? VOLUME_SELECT[family] : null
		if (!select) return listAll(path, ttlMs)
		List<Map> items
		try {
			items = listAll("${path}?select=${select}".toString(), ttlMs)   // keys are fixed: commas sent as is, as measured
		} catch (DsccException e) {
			if (e.status != 400) throw e
			List<Map> plain = listAll(path, ttlMs)   // throws again when select wasn't the problem
			if (e.errorCode == 'ERR_INVALID_KEY') selectRefused(family, 'DSCC refused a field name (ERR_INVALID_KEY)')
			else selectPaused(family, "DSCC answered it with HTTP 400${e.errorCode ? " ${e.errorCode}" : ''}")
			return plain
		}
		String wrong = selectedPageProblem(family, items)
		if (wrong) {
			List<Map> plain = listAll(path, ttlMs)
			if (!selectedPageProblem(family, plain)) selectRefused(family, wrong)   // else the data is like that: not select's fault
			return plain
		}
		return items
	}

	boolean usesSelect(int family) {
		if (noSelect.contains(family)) return false
		Long until = selectPausedUntil[family]
		return until == null || (clock.call() as long) >= until
	}

	private void selectRefused(int family, String why) {
		if (noSelect.add(family)) log.info("dscc-morpheus: reading device-type${family} volumes without select from now on: ${why}")
	}

	private void selectPaused(int family, String why) {
		long now = clock.call() as long
		Long before = selectPausedUntil.put(family, now + SELECT_PAUSE_MS)
		if (before == null || before <= now) log.info("dscc-morpheus: reading device-type${family} volumes without select for an hour: ${why}")
	}

	/** Why a selected page can't be right (null when it looks fine): no item has a device id, a state or (dt4) a size. */
	static String selectedPageProblem(int family, List<Map> items) {
		if (!items) return null
		if (!items.any { family == 2 ? it.serial_number : it.wwn }) return 'its answers had no device ids'
		if (!items.any { family == 2 ? (it.vol_state != null || it.online != null) : overallState(it) != null }) return 'its answers had no volume states'
		if (family == 4 && !items.any { mp4Size(it) != null }) return 'its answers had no volume sizes'
		return null
	}

	private static Object overallState(Map v) {
		Object st = v?.state
		return st instanceof Map ? ((Map) st).overall : st
	}

	private static Object mp4Size(Map v) {
		Object cap = v?.volumeCapacity
		return (cap instanceof Map ? ((Map) cap).sizeMiB : null) ?: v?.sizeMiB
	}

	/** Stops this client: its running build ends at its next DSCC call. */
	void stop() { stopped = true }

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

		if (stopped) throw new DsccStoppedException()
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
		if (r.status == 429) {
			Long wait = retryAfterMs(r)
			log.warn("dscc-morpheus: ${what}: rate limited by DSCC (HTTP 429)${wait != null ? ", next read in ${wait.intdiv(1000L)} s" : ''}")
			return new DsccRateLimitException("${what}: rate limited by DSCC (HTTP 429), the plugin tries again later", wait)
		}
		// DSCC's response body can hold internal detail: log it, don't return it.
		log.warn("dscc-morpheus: ${what}: HTTP ${r.status} ${SPACES.matcher(body).replaceAll(' ').take(500)}")
		java.util.regex.Matcher code = ERROR_CODE.matcher(body)
		return new DsccException("${what}: HTTP ${r.status}. Details are in the Morpheus log.", r.status, code.find() ? code.group(1) : null)
	}

	/**
	 * How long a 429 asks to wait: Retry-After (seconds or an HTTP date), else GreenLake's ratelimit-reset
	 * (seconds until the limit resets). Null when neither is there or readable; capped at MAX_RETRY_AFTER_MS.
	 */
	Long retryAfterMs(HttpResult r) {
		Long ms = null
		String ra = r.header('Retry-After')?.trim()
		if (ra) {
			if (ra ==~ /\d{1,9}/) ms = (ra as long) * 1000L
			else {
				try { ms = ZonedDateTime.parse(ra, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - (clock.call() as long) } catch (Exception ignored) { }
			}
		}
		String reset = r.header('ratelimit-reset')?.trim()
		if (ms == null && reset ==~ /\d{1,9}/) ms = (reset as long) * 1000L
		if (ms == null) return null
		return Math.min(Math.max(0L, ms), MAX_RETRY_AFTER_MS)
	}

	private static String shortPath(String p) { QUERY.matcher(API_PREFIX.matcher(p).replaceFirst('')).replaceFirst('') }

	private static String hostOf(String url) {
		try { return new URI(url).host } catch (Exception ignored) { return url }
	}

	/** Percent-encodes a path segment or query value. */
	static String enc(String s) { URLEncoder.encode(s ?: '', 'UTF-8').replace('+', '%20') }
}
