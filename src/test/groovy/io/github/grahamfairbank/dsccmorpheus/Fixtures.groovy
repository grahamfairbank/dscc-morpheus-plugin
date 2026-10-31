package io.github.grahamfairbank.dsccmorpheus

import groovy.json.JsonOutput

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * Synthetic test data in the shapes measured on a live DSCC workspace and Morpheus 9.0.1 (2026-10-29).
 * Every id, WWN, serial and name here is made up.
 *
 * Arrays: one Alletra MP (device-type4, degraded) and one Nimble group (device-type2); no device-type1.
 * Cluster: two hosts; a GFS2 datastore on the Nimble LUN, a GFS2 datastore on an MP LUN, an integrated MP datastore
 * with two per-disk LUNs, a host-only MP LUN DSCC doesn't list (snapshot) seen by one host, and a local disk.
 */
class Fixtures {
	static final String MP_ID = 'TST4000001'
	static final String NIMBLE_ID = '00aa000000000000000000000000000000000000000001'

	static final String WWN_DS = '60002AC0000000000000000A0001B000'   // GFS2 datastore mp-ds01
	static final String WWN_VM1 = '60002AC0000000000000000B0001B000'  // vm-a data disk (integrated)
	static final String WWN_VM2 = '60002AC0000000000000000C0001B000'  // vm-b root disk (integrated)
	static final String WWN_SNAP = '60002ac0000000000000000d0001b000'   // on one host only, not a DSCC volume
	static final String WWN_LEFTOVER = '60002AC0000000000000000E0001B000'  // 'unused-vol': on hosts, no VM or datastore
	static final String NIMBLE_SERIAL = 'aaaa1111bbbb22226c9ce900aabbccdd'  // GFS2 datastore nimble-ds01
	static final String LOCAL = '0x600508b1001c00000000000000000001'  // Smart Array boot disk

	static final String DT1_ID = 'TST1000001'

	/** An optional device-type1 array (FakeDscc.withDt1): top-level sizes in MiB, no volumeCapacity. */
	static Map dt1System() {
		[id: DT1_ID, name: 'primera-a', model: 'HPE Alletra 9060', state: [overall: 'STATE_NORMAL'], version: [display: '9.5.0'],
		 consoleUri: "/data-ops-manager/storage-systems/device-type1/${DT1_ID}".toString()]
	}

	static Map dt1Capacity() { [capacitySummary: [total: 1048576, free: 524288]] }

	static List<Map> dt1Volumes() {
		[[id: 'p1', name: 'primera-vol-1', wwn: '60002AC0000000000000002100010001', sizeMiB: 102400, usedSizeMiB: 2048,
		  state: [overall: 'STATE_NORMAL'], userCpgName: 'SSD_r6', snapshotCpgName: 'SSD_r6_snap', healthState: 1,
		  consoleUri: "/block/volumes/device-type1/p1?systemUid=${DT1_ID}".toString()],
		 // Only a snapshot CPG: the pool comes from snapshotCpgName
		 [id: 'p2', name: 'primera-vol-2', wwn: '60002AC0000000000000002200010001', sizeMiB: 51200, usedSizeMiB: 0,
		  state: [overall: 'STATE_DEGRADED'], snapshotCpgName: 'FC_r6_snap',
		  consoleUri: "/block/volumes/device-type1/p2?systemUid=${DT1_ID}".toString()]]
	}

	static Map dt4System() {
		[id: MP_ID, name: 'mp-array-a', model: 'HPE Alletra Storage MP B10120', overallState: 'DEGRADED',
		 state: [detailed: null, overall: 'STATE_NORMAL'], version: [display: '10.6.0', full: '10.6.0.74'],
		 consoleUri: "/data-ops-manager/storage-systems/device-type4/${MP_ID}".toString()]
	}

	static Map dt4Capacity() {
		[id: 'c1', systemId: MP_ID, capacitySummary: [total: 10485760, free: 4194304, allocated: [total: 6291456], dataReduction: 3.3]]   // MiB: 10 TiB, 4 TiB free
	}

	/** device-type4 keeps sizes in volumeCapacity (its schema has no top-level sizeMiB/usedSizeMiB). */
	static Map dt4Volume(String id, String name, String wwn, long sizeMiB, long usedMiB, String state = 'STATE_NORMAL') {
		[id: id, name: name, wwn: wwn, volumeCapacity: [sizeMiB: sizeMiB, usedSizeMiB: usedMiB, userSpace: [usedMiB: usedMiB]],
		 userCpgName: 'SSD_r6', usrCpgShortName: 'SSD_r6', healthState: 3,
		 state: [detailed: null, overall: state],
		 consoleUri: "/block/volumes/device-type4/${id}?systemUid=${MP_ID}".toString()]
	}

	static List<Map> dt4Volumes() {
		[dt4Volume('v1', 'mp-ds01', WWN_DS, 2097152, 524288),
		 dt4Volume('v2', 'HPE_VM_vm-a-data', WWN_VM1, 102400, 102400),
		 dt4Volume('v3', 'HPE_VM_vm-b-root', WWN_VM2, 40960, 40960, 'STATE_DEGRADED'),
		 dt4Volume('v4', 'unused-vol', '60002AC0000000000000000E0001B000', 1024, 0),
		 // Thin volume: fractional MiB in volumeCapacity, as measured
		 dt4Volume('v5', 'thin-vol', '60002AC000000000000000100001B000', 0, 0).with {
			it.volumeCapacity = [sizeMiB: 2097152, usedSizeMiB: 146266.5, userSpace: [usedMiB: 146266.5]]; it }]
	}

	static Map dt2System() {
		[id: NIMBLE_ID, name: 'nimble-grp-a', version: '6.1.2.800-1084673-opt', usable_capacity_bytes: 10995116277760L,
		 free_space: 8796093022208L, usage: 2199023255552L, data_reduction_ratio: 25.68988797710914,
		 arrays: [items: [[name: 'nimble-a1', status: 'reachable', model: '6010', extended_model: '6010-QP-23T', version: '6.1.2.800-1084673-opt']], total: 1],
		 consoleUri: "/data-ops-manager/storage-systems/device-type2/${NIMBLE_ID}".toString()]
	}

	static List<Map> dt2Health(boolean ok = true) {
		[[id: 'h1', element_result: [element_name: 'nimble-a1', error_list: ok ? ['SM_ok'] : ['SM_fan_fault'], messages: []]]]
	}

	static List<Map> dt2Volumes() {
		[[id: 'n1', name: 'nimble-ds01', serial_number: NIMBLE_SERIAL, size: 2097152, total_usage_bytes: 549755813888L,
		  vol_state: 'online', online: true, space_usage_level: 'normal', pool_name: 'default',
		  consoleUri: "/block/volumes/device-type2/n1?systemUid=${NIMBLE_ID}".toString()]]
	}

	/** Morpheus facts for the two-host cluster. */
	static List<HostFacts> hosts() {
		List<Map> shared = [
			[deviceId: WwnMatcher.norm("/dev/mapper/3${WWN_DS.toLowerCase()}"), device: "/dev/mapper/3${WWN_DS.toLowerCase()}".toString(), sizeBytes: 2199023255552L],
			[deviceId: WwnMatcher.norm("/dev/mapper/3${WWN_VM1.toLowerCase()}"), device: "/dev/mapper/3${WWN_VM1.toLowerCase()}".toString(), sizeBytes: 107374182400L],
			// Integration LUN of the running vm-b is NOT listed (Morpheus 9.0.1 doesn't list them on hosts). A leftover volume is.
			[deviceId: WwnMatcher.norm(WWN_LEFTOVER), device: "/dev/mapper/3${WWN_LEFTOVER.toLowerCase()}".toString(), sizeBytes: 1073741824L],
			[deviceId: WwnMatcher.norm("/dev/mapper/2${NIMBLE_SERIAL}"), device: "/dev/mapper/2${NIMBLE_SERIAL}".toString(), sizeBytes: 2199023255552L],
			[deviceId: WwnMatcher.norm(LOCAL), device: '/dev/sdb', sizeBytes: 480060241920L]
		]
		[new HostFacts(id: 1L, name: 'host-a', typeCode: 'mvmHost', vmHypervisor: true, clusterId: 7L,
			luns: shared + [[deviceId: WWN_SNAP, device: "/dev/mapper/3${WWN_SNAP}".toString(), sizeBytes: 1073741824L]]),
		 new HostFacts(id: 2L, name: 'host-b', typeCode: 'mvmHost', vmHypervisor: true, clusterId: 7L, luns: shared.collect { new LinkedHashMap(it) })]
	}

	static List<DatastoreFacts> datastores() {
		[new DatastoreFacts(id: 11L, name: 'nimble-ds01', typeCode: 'libvirt-dir-gfs2', lunId: WwnMatcher.norm("/dev/mapper/2${NIMBLE_SERIAL}"),
			sizeBytes: 2199023255552L, freeBytes: 1099511627776L, hostIds: [1L, 2L]),
		 new DatastoreFacts(id: 12L, name: 'mp-ds01', typeCode: 'libvirt-dir-gfs2', lunId: WwnMatcher.norm("/dev/mapper/3${WWN_DS}"),
			sizeBytes: 2199023255552L, freeBytes: 2000000000000L, hostIds: [1L, 2L]),
		 new DatastoreFacts(id: 13L, name: 'Alletra MP integrated', typeCode: 'hpedatastore-alletra-mp', lunId: null,
			sizeBytes: 6899864961024L, freeBytes: 5429912403968L, hostIds: [1L, 2L]),
		 new DatastoreFacts(id: 14L, name: 'local', typeCode: 'libvirt-dir', lunId: null, sizeBytes: 468145389568L, freeBytes: 402471972864L)]
	}

	static List<GuestFacts> guests() {
		[new GuestFacts(id: 101L, name: 'vm-a', hostId: 1L, running: true, disks: [
			// A file on the Nimble GFS2 datastore. Morpheus can keep a stale wwn from an earlier LUN: the datastore wins.
			new DiskFacts(id: 1001L, name: 'root', device: '/dev/vda', sizeBytes: 42949672960L, lunId: WwnMatcher.norm(WWN_VM2),
				datastoreId: 11L, datastoreName: 'nimble-ds01'),
			new DiskFacts(id: 1002L, name: 'data-1', device: '/dev/vdb', sizeBytes: 107374182400L, lunId: WwnMatcher.norm(WWN_VM1),
				datastoreId: 13L, datastoreName: 'Alletra MP integrated'),
			// On a plain directory datastore, with a stale wwn left from an earlier LUN: not an array disk.
			new DiskFacts(id: 1003L, name: 'scratch', device: '/dev/vdc', sizeBytes: 10737418240L,
				lunId: WwnMatcher.norm('60002AC0000000000000000F0001B000'), datastoreId: 14L, datastoreName: 'local')]),
		 new GuestFacts(id: 102L, name: 'vm-b', hostId: 2L, running: true, disks: [
			new DiskFacts(id: 1004L, name: 'root', device: '/dev/vda', sizeBytes: 42949672960L,
				lunId: WwnMatcher.norm("/dev/mapper/3${WWN_VM2}"), datastoreId: 13L, datastoreName: 'Alletra MP integrated')])]
	}

	static DsccSettings settings(Map extra = [:]) {
		DsccSettings.fromMap([dsccClientId: 'client-1', dsccClientSecret: 'secret-1', dsccWorkspaceId: 'ws000000000000000000000000000001'] + extra)
	}
}

/**
 * A DSCC that answers like the real one: workspace token, HTTP 400 without both limit and offset, paging by
 * limit/offset with total, and ERROR_FAILED_TO_AUTHORIZE for legacy tokens.
 */
class FakeDscc implements DsccTransport {
	List<String> calls = []
	boolean legacyOnly = false
	boolean nimbleHealthy = true
	int pageLimit = 500
	Map<String, Object> override = [:]
	/** device-type4 volumes to serve instead of Fixtures.dt4Volumes(). */
	List<Map> dt4VolumeItems = null
	/** Serve Fixtures.dt1System() as a device-type1 array. */
	boolean withDt1 = false
	/** When true, any select is refused (with selectErrorCode, ERR_INVALID_KEY as for an unknown key). */
	boolean selectRefused = false
	String selectErrorCode = 'ERR_INVALID_KEY'
	/** Fields DSCC accepts in select but leaves out of its answer (to test the sanity check). */
	Map<Integer, Set<String>> selectDrops = [:]
	/**
	 * Volume list keys select may name, per family (a subset of storage-api.json, checked 2026-10-31). Any other
	 * key gets HTTP 400 ERR_INVALID_KEY, as on DSCC.
	 */
	Map<Integer, Set<String>> volumeKeys = [
		1: ['id', 'name', 'wwn', 'sizeMiB', 'usedSizeMiB', 'state', 'userCpgName', 'snapshotCpgName', 'consoleUri'] as Set,
		4: ['id', 'name', 'wwn', 'volumeCapacity', 'volumeCapacity.sizeMiB', 'volumeCapacity.usedSizeMiB', 'state', 'userCpgName',
		    'usrCpgShortName', 'consoleUri'] as Set,
		2: ['id', 'name', 'serial_number', 'size', 'total_usage_bytes', 'vol_usage_compressed_bytes', 'vol_state', 'online',
		    'offline_reason', 'space_usage_level', 'pool_name', 'consoleUri'] as Set]
	/** Called before every GET; may throw (to simulate transport errors) or advance a test clock. */
	Closure onGet = null

	HttpResult send(String method, String url, Map<String, String> headers, String body) {
		calls << "${method} ${url}".toString()
		if (method == 'POST') {
			if (url.contains('/authorization/v2/oauth2/') && !legacyOnly) return ok([access_token: 'ws-token', expires_in: 899])
			if (url.contains('sso.common.cloud.hpe.com')) return ok([access_token: 'legacy-token', expires_in: 7199])
			return new HttpResult(401, '{"error":"invalid_client"}')
		}
		if (headers.Authorization == 'Bearer legacy-token') {
			return new HttpResult(400, '{"error":"ERROR_FAILED_TO_AUTHORIZE - []","errorCode":"DOWNSTREAM_SERVICE_ERROR"}')
		}
		if (onGet != null) onGet.call(url)
		return answer(url)
	}

	private HttpResult answer(String url) {
		URI u = new URI(url)
		Map<String, String> q = (u.rawQuery ?: '').split('&').findAll().collectEntries { String kv -> def p = kv.split('=', 2); [(p[0]): p.size() > 1 ? p[1] : ''] }
		String p = u.path
		if (override.containsKey(p)) return (HttpResult) override[p]
		boolean list = p ==~ /.*(\/device-type\d|\/volumes|\/health-status)$/
		if (list && (q.limit == null) != (q.offset == null)) {
			return new HttpResult(400, '{"error":"Please provide both limit and offset query parameters for pagination","errorCode":"ERR_QUERY_PARAM_VALIDATION"}')
		}
		def vol = p =~ /\/device-type(\d)\/[^\/]+\/volumes$/
		if (q.select != null && vol.find()) {
			int fam = vol.group(1) as int
			List<String> keys = URLDecoder.decode(q.select, 'UTF-8').split(',') as List<String>
			if (selectRefused) return new HttpResult(400, '{"error":"select refused","errorCode":"' + selectErrorCode + '"}')
			if (!keys.every { it in volumeKeys[fam] }) {
				return new HttpResult(400, '{"error":"invalid key in select","errorCode":"ERR_INVALID_KEY"}')
			}
			HttpResult full = answer(url.replaceFirst('[?&]select=[^&]*', '').replace('/volumes&', '/volumes?'))
			if (full.status != 200) return full
			Map b = (Map) new groovy.json.JsonSlurper().parseText(full.body)
			b.items = ((List<Map>) b.items).collect { Map it -> project(it, keys - (selectDrops[fam] ?: ([] as Set))) }
			return ok(b)
		}
		switch (p) {
			case '/api/v1/storage-systems/device-type1': return page(withDt1 ? [Fixtures.dt1System()] : [], q)
			case "/api/v1/storage-systems/device-type1/${Fixtures.DT1_ID}/capacity-summary".toString(): return ok(Fixtures.dt1Capacity())
			case "/api/v1/storage-systems/device-type1/${Fixtures.DT1_ID}/volumes".toString(): return page(Fixtures.dt1Volumes(), q)
			case '/api/v1/storage-systems/device-type2': return page([Fixtures.dt2System()], q)
			case '/api/v1/storage-systems/device-type4': return page([Fixtures.dt4System()], q)
			case "/api/v1/storage-systems/device-type4/${Fixtures.MP_ID}/capacity-summary".toString(): return ok(Fixtures.dt4Capacity())
			case "/api/v1/storage-systems/device-type4/${Fixtures.MP_ID}/volumes".toString(): return page(dt4VolumeItems ?: Fixtures.dt4Volumes(), q)
			case "/api/v1/storage-systems/device-type2/${Fixtures.NIMBLE_ID}/health-status".toString(): return page(Fixtures.dt2Health(nimbleHealthy), q)
			case "/api/v1/storage-systems/device-type2/${Fixtures.NIMBLE_ID}/volumes".toString(): return page(Fixtures.dt2Volumes(), q)
		}
		return new HttpResult(404, '{"error":"not found"}')
	}

	/** Keeps only the selected fields; "a.b" keeps b inside a. Fields the item doesn't have stay absent. */
	static Map project(Map item, List<String> keys) {
		Map out = [:]
		keys.each { String k ->
			int dot = k.indexOf('.')
			if (dot < 0) { if (item.containsKey(k)) out[k] = item[k]; return }
			String top = k.substring(0, dot), sub = k.substring(dot + 1)
			Object nested = item[top]
			if (nested instanceof Map && ((Map) nested).containsKey(sub)) {
				Map m = (Map) out.computeIfAbsent(top) { [:] }
				m[sub] = ((Map) nested)[sub]
			}
		}
		return out
	}

	private HttpResult page(List items, Map<String, String> q) {
		int limit = Math.min((q.limit ?: '500') as int, pageLimit)
		int offset = (q.offset ?: '0') as int
		List slice = items.drop(offset).take(limit)
		return ok([items: slice, total: items.size(), pageLimit: limit, pageOffset: offset])
	}

	private static HttpResult ok(Object o) { new HttpResult(200, JsonOutput.toJson(o)) }
}

/** Runs submitted tasks on the caller's thread, so background index builds finish before asserts. */
class DirectExecutor extends AbstractExecutorService {
	boolean shut
	void execute(Runnable r) { r.run() }
	void shutdown() { shut = true }
	List<Runnable> shutdownNow() { shut = true; [] }
	boolean isShutdown() { shut }
	boolean isTerminated() { shut }
	boolean awaitTermination(long t, TimeUnit u) { true }
}
