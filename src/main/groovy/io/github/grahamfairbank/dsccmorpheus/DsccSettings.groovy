package io.github.grahamfairbank.dsccmorpheus

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

/**
 * The plugin's settings (Administration > Integrations > Plugins > dscc-morpheus), parsed on each use,
 * plus the URLs derived from them. Free of Morpheus classes so it can be unit tested on its own.
 */
@CompileStatic
class DsccSettings {

	// Field names used in DsccMorpheusPlugin's option types. Morpheus stores settings as JSON keyed by these.
	static final String REGION = 'dsccRegion'
	static final String CLIENT_ID = 'dsccClientId'
	static final String CLIENT_SECRET = 'dsccClientSecret'
	static final String WORKSPACE_ID = 'dsccWorkspaceId'
	static final String TOKEN_URL = 'dsccTokenUrl'
	static final String UI_URL = 'dsccUiUrl'
	static final String PROXY_URL = 'proxyUrl'
	static final String REQUIRE_PERMISSION = 'requirePermission'
	static final String SHOW_ALWAYS = 'showAlways'

	static final String DEFAULT_REGION = 'us1'
	static final List<String> REGIONS = ['us1', 'eu1', 'jp1']
	static final String LEGACY_TOKEN_URL = 'https://sso.common.cloud.hpe.com/as/token.oauth2'

	final String apiUrl
	final String region
	final String clientId
	final String clientSecret
	final String workspaceId
	final String tokenUrl
	final String uiUrl
	final String proxyUrl
	final boolean requirePermission
	final boolean showAlways

	private DsccSettings(Map m) {
		String r = str(m[REGION])?.toLowerCase()
		if (r && httpsUrl(r)) {
			apiUrl = httpsUrl(r)
			region = regionOf(apiUrl)
		} else {
			region = r in REGIONS ? r : DEFAULT_REGION
			apiUrl = "https://${region}.data.cloud.hpe.com".toString()
		}
		clientId = str(m[CLIENT_ID])
		clientSecret = str(m[CLIENT_SECRET])
		workspaceId = str(m[WORKSPACE_ID])?.replace('-', '')
		tokenUrl = httpsUrl(str(m[TOKEN_URL]))
		uiUrl = httpsUrl(str(m[UI_URL])) ?: "https://console-${region ?: DEFAULT_REGION}.data.cloud.hpe.com".toString()
		proxyUrl = httpUrl(str(m[PROXY_URL]))
		requirePermission = bool(m[REQUIRE_PERMISSION])
		showAlways = bool(m[SHOW_ALWAYS])
	}

	/** Parses Morpheus's settings JSON. Bad or missing JSON gives an unconfigured instance, never an exception. */
	static DsccSettings fromJson(String json) {
		Map m = [:]
		if (json?.trim()) {
			try {
				Object parsed = new JsonSlurper().parseText(json)
				if (parsed instanceof Map) m = (Map) parsed
			} catch (Exception ignored) {
				// fall through with defaults
			}
		}
		return new DsccSettings(m)
	}

	static DsccSettings fromMap(Map m) { new DsccSettings(m ?: [:]) }

	/** True when there is enough to call DSCC. */
	boolean isConfigured() { clientId && clientSecret }

	/**
	 * Token endpoints to try, in order. Measured 2026-10-29 on a multi-workspace account: DSCC authorizes only the
	 * workspace issuer's token (it carries hpe_workspace_id); the legacy SSO token is issued but every storage read
	 * then fails with ERROR_FAILED_TO_AUTHORIZE. So the workspace issuers go first and legacy SSO is the fallback.
	 */
	List<String> tokenUrls() {
		if (tokenUrl) return [tokenUrl]
		List<String> urls = []
		if (workspaceId) {
			urls << "https://global.api.greenlake.hpe.com/authorization/v2/oauth2/${workspaceId}/token".toString()
			urls << "https://aquila-org-api.common.cloud.hpe.com/authorization/v2/oauth2/${workspaceId}/token".toString()
		}
		urls << LEGACY_TOKEN_URL
		return urls
	}

	/** DSCC console link for a consoleUri (relative path) from the API, or null. */
	String consoleLink(Object consoleUri) {
		String u = consoleUri?.toString()?.trim()
		if (!u || u == 'null' || !u.startsWith('/')) return null
		return uiUrl + u
	}

	/** Same identity whenever the values that affect API calls are the same; used to reset caches. */
	String connectionKey() {
		// A hash, so the service doesn't keep one more plain copy of the secret for the plugin's lifetime.
		byte[] d = java.security.MessageDigest.getInstance('SHA-256')
			.digest([apiUrl, clientId, clientSecret, workspaceId, tokenUrl, proxyUrl].join('\u0000').getBytes('UTF-8'))
		return d.encodeHex().toString()
	}

	static String regionOf(String url) {
		String host = hostOf(url)
		if (host?.endsWith('.data.cloud.hpe.com')) {
			String r = host.substring(0, host.indexOf('.'))
			return r in REGIONS ? r : DEFAULT_REGION
		}
		return DEFAULT_REGION
	}

	private static String hostOf(String url) {
		try { return url ? new URI(url).host : null } catch (Exception ignored) { return null }
	}

	private static String str(Object o) {
		String s = o?.toString()?.trim()
		return s ? s : null
	}

	private static boolean bool(Object o) {
		String s = o?.toString()?.trim()?.toLowerCase()
		return s in ['on', 'true', '1', 'yes']
	}

	/** https URL without trailing slashes, or null. */
	private static String httpsUrl(String s) {
		if (!s) return null
		String u = s.replaceAll('/+$', '')
		return u.toLowerCase().startsWith('https://') && hostOf(u) ? u : null
	}

	private static String httpUrl(String s) {
		if (!s) return null
		String u = s.replaceAll('/+$', '')
		return (u.toLowerCase().startsWith('http://') || u.toLowerCase().startsWith('https://')) && hostOf(u) ? u : null
	}
}
