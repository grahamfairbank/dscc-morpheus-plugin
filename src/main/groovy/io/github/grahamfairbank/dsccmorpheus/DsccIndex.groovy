package io.github.grahamfairbank.dsccmorpheus

import groovy.util.logging.Slf4j

import java.util.regex.Pattern

/** One storage system (array or Nimble group) in DSCC, in the units and words the tabs use. */
class ArraySystem {
	String key          // "dt4:<id>": unique across families
	String id
	int family
	String familyName
	String name
	String model
	String version
	String state        // display text
	String level        // ok | warn | bad | muted
	Long totalBytes
	Long freeBytes
	/** Data reduction ratio (dedupe and compression), e.g. 3.3 for 3.3:1; null when unknown. */
	Double reduction
	String url
	String note         // set when part of this system's data could not be read
	/** When this system's volumes were read (clock ms); older than the index when they were carried over. */
	Long volumesAt

	Long getUsedBytes() { totalBytes != null && freeBytes != null ? Math.max(0L, totalBytes - freeBytes) : null }
}

/** One DSCC volume, keyed in the index by its normalized device id. */
class ArrayVolume {
	String id
	String name
	String systemKey
	String deviceId     // normalized (WwnMatcher.norm)
	Long sizeBytes
	Long usedBytes
	String pool
	String state
	String level
	String url
}

/**
 * Every DSCC storage system and volume in the workspace, read in one pass and looked up by device id.
 * Built in the background by DsccService and replaced whole on refresh, so readers never see a half-built index.
 *
 * Units and fields were measured on a live workspace (2026-10-29):
 * - device-type1/4: volume sizeMiB, usedSizeMiB, state.overall; system overallState; capacity-summary total/free in MiB.
 * - device-type2: volume size in MiB, usage in bytes, vol_state/online/space_usage_level; system usable_capacity_bytes,
 *   free_space in bytes; health from /health-status error_list (SM_ok = healthy).
 * - Pool: device-type1/4 userCpgName (device-type4 also usrCpgShortName), snapshotCpgName; device-type2 pool_name.
 */
@Slf4j
class DsccIndex {

	static final long MIB = 1024L * 1024L
	static final long LIST_TTL_MS = 0L   // the index itself is the cache
	private static final Pattern STATE_PREFIX = Pattern.compile('^STATE_')

	static final Map<Integer, String> FAMILY_NAMES = [
		1: 'HPE 3PAR / Primera / Alletra 9000',
		2: 'HPE Nimble / Alletra 5000 / 6000',
		4: 'HPE Alletra Storage MP B10000'
	]

	final long builtAt
	final Map<String, ArraySystem> systems
	final Map<String, ArrayVolume> volumes
	final List<String> notes

	DsccIndex(long builtAt, Map<String, ArraySystem> systems, Map<String, ArrayVolume> volumes, List<String> notes = []) {
		this.builtAt = builtAt
		this.systems = Collections.unmodifiableMap(systems)
		this.volumes = Collections.unmodifiableMap(volumes)
		this.notes = Collections.unmodifiableList(notes)
	}

	ArrayVolume volume(String deviceId) { deviceId ? volumes[deviceId] : null }

	ArraySystem systemOf(ArrayVolume v) { v ? systems[v.systemKey] : null }

	/**
	 * Reads every system and volume. A failing system list fails the build. A system whose volumes can't be read
	 * keeps its volumes from the previous index (with a note), so its LUNs don't turn into "not a DSCC volume".
	 * A 429 or the time budget stops the whole build: the caller keeps the previous index.
	 */
	static DsccIndex build(DsccClient c, long now, DsccIndex previous = null) {
		Map<String, ArraySystem> systems = [:]
		Map<String, ArrayVolume> volumes = [:]
		List<String> notes = []
		DsccClient.FAMILIES.each { int fam ->
			List<Map> list = c.systems(fam, LIST_TTL_MS)
			list.each { Map s ->
				ArraySystem sys = fam == 2 ? nimbleSystem(s, c) : primeraSystem(fam, s, c)
				// The system list can omit consoleUri (seen for device-type4); the detail page path is fixed.
				sys.url = c.settings.consoleLink(s.consoleUri) ?:
					c.settings.consoleLink("/data-ops-manager/storage-systems/device-type${fam}/${DsccClient.enc(sys.id)}".toString())
				systems[sys.key] = sys
				try {
					c.volumes(fam, sys.id, LIST_TTL_MS).each { Map v ->
						ArrayVolume av = fam == 2 ? nimbleVolume(v, sys.key) : primeraVolume(v, sys.key)
						if (av.deviceId) {
							av.url = c.settings.consoleLink(v.consoleUri)
							volumes[av.deviceId] = av
						}
					}
					sys.volumesAt = now
				} catch (DsccBudgetException | DsccRateLimitException e) {
					throw e
				} catch (DsccException e) {
					ArraySystem old = previous?.systems?.get(sys.key)
					List<ArrayVolume> kept = previous ? previous.volumes.values().findAll { it.systemKey == sys.key } as List<ArrayVolume> : []
					kept.each { ArrayVolume av -> volumes.putIfAbsent(av.deviceId, av) }
					if (kept) {
						sys.volumesAt = old?.volumesAt ?: previous.builtAt
						sys.note = "Couldn't read this system's volumes (${e.message}), so its volumes are from ${DsccView.when(sys.volumesAt)}".toString()
					} else {
						sys.note = "Couldn't read this system's volumes: ${e.message}".toString()
					}
					notes << "${sys.name}: ${sys.note}".toString()
				}
			}
		}
		log.info("dscc-morpheus: indexed ${systems.size()} storage systems and ${volumes.size()} volumes")
		return new DsccIndex(now, systems, volumes, notes)
	}

	// ---- device-type1 (3PAR, Primera, Alletra 9000) and device-type4 (Alletra MP) ------------------

	static ArraySystem primeraSystem(int fam, Map s, DsccClient c) {
		ArraySystem sys = new ArraySystem(key: "dt${fam}:${s.id}".toString(), id: s.id as String, family: fam,
			familyName: FAMILY_NAMES[fam], name: (s.name ?: s.id) as String,
			model: words(s.model ?: ((Map) (s.manufacturing ?: [:])).model) ?: '--',
			version: version(s.version) ?: (s.softwareVersion as String) ?: '--')
		String overall = (s.overallState ?: ((Map) (s.state ?: [:])).overall) as String
		sys.state = stateWords(overall)
		sys.level = stateLevel(overall)
		try {
			Map cs = (Map) (c.capacitySummary(fam, sys.id, LIST_TTL_MS)?.capacitySummary ?: [:])
			sys.totalBytes = mib(cs.total)
			sys.freeBytes = mib(cs.free)
			sys.reduction = ratio(cs.dataReduction)
		} catch (DsccBudgetException | DsccRateLimitException e) {
			throw e
		} catch (DsccException e) {
			sys.note = "Couldn't read capacity: ${e.message}".toString()
		}
		return sys
	}

	static ArrayVolume primeraVolume(Map v, String systemKey) {
		Map st = (Map) (v.state instanceof Map ? v.state : [:])
		String overall = (st.overall ?: v.state) as String
		// Thin Alletra MP volumes leave sizeMiB/usedSizeMiB null and report them in volumeCapacity (fractional MiB).
		Map cap = (Map) (v.volumeCapacity instanceof Map ? v.volumeCapacity : [:])
		return new ArrayVolume(id: v.id as String, name: v.name as String, systemKey: systemKey,
			deviceId: WwnMatcher.norm(v.wwn), sizeBytes: mib(v.sizeMiB ?: cap.sizeMiB), usedBytes: mib(v.usedSizeMiB ?: cap.usedSizeMiB),
			pool: (v.userCpgName ?: v.usrCpgShortName ?: v.snapshotCpgName) as String, state: stateWords(overall), level: stateLevel(overall))
	}

	// ---- device-type2 (Nimble, Alletra 5000/6000) --------------------------------------------------

	static ArraySystem nimbleSystem(Map s, DsccClient c) {
		Map first = (Map) ((List) (((Map) (s.arrays ?: [:])).items ?: []))[0] ?: [:]
		ArraySystem sys = new ArraySystem(key: "dt2:${s.id}".toString(), id: s.id as String, family: 2,
			familyName: FAMILY_NAMES[2], name: (s.name ?: s.id) as String,
			model: (first.extended_model ?: first.model ?: '--') as String,
			version: (s.version ?: first.version ?: '--') as String,
			totalBytes: num(s.usable_capacity_bytes ?: first.usable_capacity_bytes),
			freeBytes: num(s.free_space ?: first.available_bytes), reduction: ratio(s.data_reduction_ratio))
		List<String> problems = []
		List arrays = (List) (((Map) (s.arrays ?: [:])).items ?: [])
		arrays.each { Map a -> if (a.status && a.status != 'reachable') problems << "${a.name ?: 'array'} ${a.status}".toString() }
		try {
			c.healthStatus(sys.id, LIST_TTL_MS).each { Map h ->
				List errs = (List) (((Map) (h.element_result ?: [:])).error_list ?: [])
				errs.findAll { it && it != 'SM_ok' }.each { problems << it.toString() }
			}
			sys.state = problems ? "Attention: ${problems.unique().take(3).join(', ')}".toString() : 'Healthy'
			sys.level = problems ? 'warn' : 'ok'
		} catch (DsccBudgetException | DsccRateLimitException e) {
			throw e
		} catch (DsccException e) {
			sys.state = problems ? "Attention: ${problems.take(3).join(', ')}".toString() : 'Unknown'
			sys.level = problems ? 'warn' : 'muted'
			sys.note = "Couldn't read health: ${e.message}".toString()
		}
		return sys
	}

	static ArrayVolume nimbleVolume(Map v, String systemKey) {
		boolean online = v.online != false && (v.vol_state == null || v.vol_state == 'online')
		String space = v.space_usage_level as String
		String level = !online ? 'bad' : (space in ['critical'] ? 'bad' : (space in ['warning'] ? 'warn' : 'ok'))
		String state = !online ? "Offline${v.offline_reason ? " (${v.offline_reason})" : ''}" :
			(space && space != 'normal' ? "Online, space ${space}" : 'Online')
		Long used = num(v.total_usage_bytes) ?: num(v.vol_usage_compressed_bytes)
		return new ArrayVolume(id: v.id as String, name: v.name as String, systemKey: systemKey,
			deviceId: WwnMatcher.norm(v.serial_number), sizeBytes: mib(v.size), usedBytes: used,
			pool: v.pool_name as String, state: state.toString(), level: level)
	}

	// ---- helpers -----------------------------------------------------------------------------------

	static String stateWords(String s) {
		if (!s) return 'Unknown'
		String w = STATE_PREFIX.matcher(s).replaceFirst('').toLowerCase().replace('_', ' ')
		return w == 'normal' ? 'Normal' : w.substring(0, 1).toUpperCase() + w.substring(1)
	}

	static String stateLevel(String s) {
		String u = STATE_PREFIX.matcher((s ?: '').toUpperCase()).replaceFirst('')
		if (u in ['NORMAL', 'OK', 'HEALTHY']) return 'ok'
		if (u in ['DEGRADED', 'WARNING', 'NEW']) return 'warn'
		if (u in ['FAILED', 'CRITICAL', 'OFFLINE']) return 'bad'
		return 'muted'
	}

	static String version(Object v) {
		if (v instanceof Map) return (((Map) v).display ?: ((Map) v).full ?: ((Map) v).base) as String
		return v as String
	}

	static Double ratio(Object v) {
		if (v == null) return null
		try { double d = new BigDecimal(v.toString()).doubleValue(); return d > 0 ? d : null } catch (Exception ignored) { return null }
	}

	static String words(Object v) { v == null ? null : v.toString().replace('_', ' ') }

	static Long mib(Object v) {
		if (v == null) return null
		try { return (new BigDecimal(v.toString()) * MIB).longValue() } catch (Exception ignored) { return null }
	}

	static Long num(Object v) {
		if (v == null) return null
		if (v instanceof Number) return ((Number) v).longValue()
		try { return new BigDecimal(v.toString()).longValue() } catch (Exception ignored) { return null }
	}
}
