package io.github.grahamfairbank.dsccmorpheus

/**
 * Joins Morpheus facts (hosts, datastores, VM disks) with the DSCC index into the template models of the three
 * tabs. Pure functions over plain objects: no Morpheus or network access, so it is unit tested with fixtures.
 *
 * Two kinds of array LUN (owner, 2026-10-29: "gfs2 AND integrated volumes from the lun used directly in the vm"):
 * - datastore LUNs: a GFS2 datastore on one LUN, shared by all hosts; VM disks are files on it;
 * - integrated LUNs: one LUN per VM disk (Alletra MP integration), used by the VM directly.
 */
class StorageMap {

	// ---- one LUN ---------------------------------------------------------------------------------

	/** Array facts for one LUN id: always a map, with matched=false when DSCC has no such volume. */
	static Map lun(String lunId, DsccIndex idx, Long morpheusSize = null) {
		Map r = [lunId: lunId, lunShort: WwnMatcher.shortId(lunId), hpe: WwnMatcher.isHpeArray(lunId)]
		ArrayVolume v = idx?.volume(lunId)
		if (!v) {
			r.matched = false
			r.statusText = r.hpe ? 'Not a DSCC volume (snapshot, or removed from the array)' : 'Not an HPE array LUN'
			r.statusLevel = r.hpe ? 'warn' : 'muted'
			r.size = DsccView.bytes(morpheusSize)
			return r
		}
		ArraySystem sys = idx.systemOf(v)
		r.putAll([matched: true, volume: v.name ?: '--', volumeUrl: v.url, pool: v.pool ?: '--',
		          size: DsccView.bytes(v.sizeBytes), used: DsccView.bytes(v.usedBytes), usedPct: DsccView.pct(v.usedBytes, v.sizeBytes),
		          statusText: v.state ?: '--', statusLevel: v.level ?: 'muted',
		          array: sys?.name ?: '--', arrayUrl: sys?.url, arrayState: sys?.state ?: '--', arrayLevel: sys?.level ?: 'muted',
		          arrayKey: sys?.key])
		return r
	}

	/**
	 * How a VM disk is stored: [kind: lun|file|other, lunId:, datastore:].
	 * The datastore decides: a GFS2 datastore means a file on its LUN; a known non-array datastore (NFS, local
	 * directory) means not on an array, even when Morpheus still holds a WWN from an earlier LUN (seen on 9.0.1).
	 * The disk's own LUN id counts only on an integrated array datastore, or when the datastore is unknown.
	 */
	static Map backing(DiskFacts d, Map<Long, DatastoreFacts> datastores) {
		DatastoreFacts ds = d.datastoreId != null ? datastores[d.datastoreId] : null
		if (ds?.lunId) return [kind: 'file', lunId: ds.lunId, datastore: ds]
		if (d.lunId && (ds == null || isIntegrated(ds))) return [kind: 'lun', lunId: d.lunId, datastore: ds]
		return [kind: 'other', lunId: null, datastore: ds]
	}

	/** An array integration datastore that provisions one LUN per disk (for example hpedatastore-alletra-mp). */
	static boolean isIntegrated(DatastoreFacts ds) { (ds?.typeCode ?: '').toLowerCase().startsWith('hpedatastore') }

	/** Who uses each LUN in a cluster: lunId -> list of "Datastore X" / "VM y: disk z". */
	static Map<String, List<Map>> usage(Collection<DatastoreFacts> datastores, Collection<GuestFacts> guests) {
		Map<String, List<Map>> out = [:].withDefault { [] }
		datastores.each { DatastoreFacts ds -> if (ds.lunId) out[ds.lunId] << [kind: 'datastore', text: "Datastore ${ds.name}".toString()] }
		Map<Long, DatastoreFacts> byId = datastores.collectEntries { [(it.id): it] }
		guests.each { GuestFacts g ->
			g.disks.each { DiskFacts d ->
				Map b = backing(d, byId)
				if (b.kind == 'lun') out[b.lunId as String] << [kind: 'vm', text: "VM ${g.name}: ${d.name ?: d.device ?: 'disk'}".toString(), vmId: g.id]
			}
		}
		return out
	}

	// ---- host tab --------------------------------------------------------------------------------

	/** Morpheus has no VM disk or datastore record for the LUN. Something outside Morpheus may still use it. */
	static final String UNUSED = 'Not linked to a VM or datastore in Morpheus'

	/**
	 * Host tab. Two sources (measured on 9.0.1): the LUNs Morpheus lists for the host (its multipath devices), and
	 * the integration disks of VMs running on this host, which Morpheus does NOT list on the host.
	 */
	static Map host(HostFacts h, List<HostFacts> clusterHosts, Collection<DatastoreFacts> datastores,
	                Collection<GuestFacts> guests, DsccIndex idx) {
		Map<String, List<Map>> users = usage(datastores, guests)
		Map<String, Integer> seen = seenCounts(clusterHosts)
		int hostCount = clusterHosts?.size() ?: 1
		Set<String> listed = h.luns*.deviceId.collect { it as String } as Set
		List<Map> rows = h.luns.findAll { WwnMatcher.isHpeArray(it.deviceId as String) }.collect { Map l ->
			String id = l.deviceId as String
			List<Map> u = users[id] ?: []
			lun(id, idx, l.sizeBytes as Long) + [device: l.device,
				usedBy: u ? u*.text.take(3).join(', ') + (u.size() > 3 ? " and ${u.size() - 3} more" : '') : UNUSED,
				usedByKind: u ? u[0].kind : 'none',
				seenOn: "${seen[id] ?: 1} of ${hostCount}".toString(), seenLevel: (seen[id] ?: 1) < hostCount ? 'warn' : 'ok']
		}
		Map<Long, DatastoreFacts> byId = datastores.collectEntries { [(it.id): it] }
		List<Map> vmRows = []
		guests.findAll { it.running && it.hostId != null && it.hostId == h.id }.each { GuestFacts g ->
			g.disks.each { DiskFacts d ->
				Map b = backing(d, byId)
				String id = b.lunId as String
				if (b.kind != 'lun' || !WwnMatcher.isHpeArray(id) || id in listed) return
				vmRows << lun(id, idx, d.sizeBytes) + [device: d.device, usedBy: "VM ${g.name}: ${d.name ?: d.device ?: 'disk'}".toString(),
					usedByKind: 'vm', seenOn: 'VM runs here', seenLevel: 'ok', vmDisk: true]
			}
		}
		rows = (rows + firstPerLun(vmRows)).sort { x, y -> (rank(x) <=> rank(y)) ?: ((x.usedBy as String) <=> (y.usedBy as String)) }
		int other = h.luns.count { !WwnMatcher.isHpeArray(it.deviceId as String) } as int
		return [state: rows ? 'host' : 'noLuns', hostName: h.name, rows: rows, otherLuns: other,
		        devicesSeen: h.devices.take(20).join(', ') ?: 'none', devicesCount: h.devices.size(),
		        totals: totals(rows) + [hosts: hostCount, vmDisks: vmRows.size(), unused: rows.count { it.usedByKind == 'none' && it.matched }]]
	}

	// ---- guest tab (VM server page and instance page) ----------------------------------------------

	static Map guest(List<GuestFacts> guests, Map<Long, DatastoreFacts> datastores, DsccIndex idx) {
		List<Map> rows = []
		guests.each { GuestFacts g ->
			g.disks.each { DiskFacts d ->
				Map b = backing(d, datastores)
				DatastoreFacts ds = (DatastoreFacts) b.datastore
				Map r = [vm: g.name, disk: d.name ?: '--', device: d.device ?: '--', diskSize: DsccView.bytes(d.sizeBytes),
				         seen: "${d.seen ?: ''}; datastore ${ds ? "${ds.name} (${ds.typeCode ?: '?'}${ds.lunId ? ', LUN ' + WwnMatcher.shortId(ds.lunId) : ''})" : (d.datastoreName ?: 'none')}".toString()]
				if (b.kind == 'other') {
					r.putAll([kind: 'other', kindText: ds ? "On datastore ${ds.name} (not an array LUN)".toString() : 'Not on an array LUN',
					          matched: false, statusText: '--', statusLevel: 'muted'])
				} else {
					r.putAll(lun(b.lunId as String, idx, d.sizeBytes))
					r.kind = b.kind
					r.kindText = b.kind == 'file' ? "File on GFS2 datastore ${ds?.name}".toString() :
						(isIntegrated(ds) ? "Alletra B10000 Integration LUN (${ds.name})".toString() : 'Own array LUN')
					if (b.kind == 'file' && ds) r.datastoreUse = "${DsccView.bytes(ds.sizeBytes)} datastore, ${DsccView.bytes(ds.freeBytes)} free".toString()
				}
				rows << r
			}
		}
		boolean any = rows.any { it.kind in ['file', 'lun'] }
		return [state: any ? 'guest' : 'noLuns', rows: rows, multi: guests.size() > 1, totals: totals(rows.findAll { it.kind != 'other' })]
	}

	// ---- cluster tab -----------------------------------------------------------------------------

	static Map cluster(String clusterName, List<HostFacts> hosts, Collection<DatastoreFacts> datastores,
	                   Collection<GuestFacts> guests, DsccIndex idx) {
		Map<String, Integer> seen = seenCounts(hosts)
		int hostCount = hosts.size()
		Map<String, List<Map>> users = usage(datastores, guests)

		// Every HPE LUN in the cluster: seen by a host, behind a datastore, or used by a VM disk.
		Set<String> all = new LinkedHashSet<>()
		hosts.each { h -> h.luns.each { if (WwnMatcher.isHpeArray(it.deviceId as String)) all << (it.deviceId as String) } }
		datastores.each { if (WwnMatcher.isHpeArray(it.lunId)) all << it.lunId }
		users.keySet().each { if (WwnMatcher.isHpeArray(it)) all << it }
		Map<String, Map> luns = all.collectEntries { [(it): lun(it, idx)] }

		List<Map> dsRows = datastores.findAll { it.lunId }.sort { it.name?.toLowerCase() }.collect { DatastoreFacts ds ->
			Map r = luns[ds.lunId] ?: lun(ds.lunId, idx)
			int n = seen[ds.lunId] ?: 0
			r + [datastore: ds.name, dsSize: DsccView.bytes(ds.sizeBytes), dsFree: DsccView.bytes(ds.freeBytes),
			     dsUsedPct: DsccView.pct(ds.sizeBytes != null && ds.freeBytes != null ? ds.sizeBytes - ds.freeBytes : null, ds.sizeBytes),
			     seenOn: "${n} of ${hostCount}".toString(), seenLevel: n < hostCount ? 'warn' : 'ok']
		}

		// Integrated LUNs (one per VM disk), grouped by the Morpheus datastore that provisions them.
		Map<String, Map> integrated = [:]
		Map<Long, DatastoreFacts> byId = datastores.collectEntries { [(it.id): it] }
		guests.each { GuestFacts g ->
			g.disks.each { DiskFacts d ->
				Map b = backing(d, byId)
				if (b.kind != 'lun' || !WwnMatcher.isHpeArray(b.lunId as String)) return
				String group = ((DatastoreFacts) b.datastore)?.name ?: d.datastoreName ?: 'No datastore'
				Map gr = integrated.computeIfAbsent(group) { [datastore: group, disks: 0, vms: new HashSet<Long>(), sizeBytes: 0L, matched: 0, arrays: new LinkedHashSet<String>()] }
				Map l = luns[b.lunId as String]
				gr.disks = (gr.disks as int) + 1
				((Set) gr.vms) << g.id
				gr.sizeBytes = (gr.sizeBytes as long) + (idx?.volume(b.lunId as String)?.sizeBytes ?: d.sizeBytes ?: 0L)
				if (l?.matched) { gr.matched = (gr.matched as int) + 1; ((Set) gr.arrays) << (l.array as String) }
			}
		}
		List<Map> intRows = integrated.values().collect { Map gr ->
			[datastore: gr.datastore, disks: gr.disks, vms: ((Set) gr.vms).size(), size: DsccView.bytes(gr.sizeBytes as Long),
			 matched: gr.matched, unmatched: (gr.disks as int) - (gr.matched as int), arrays: ((Set) gr.arrays).join(', ') ?: '--']
		}.sort { it.datastore }

		// Arrays serving this cluster.
		Map<String, Integer> lunsPerArray = [:].withDefault { 0 }
		luns.values().findAll { it.matched }.each { lunsPerArray[it.arrayKey as String]++ }
		List<Map> arrays = lunsPerArray.keySet().collect { String key ->
			ArraySystem s = idx.systems[key]
			[name: s.name, model: s.model, reduction: DsccView.ratio(s.reduction), version: s.version, state: s.state, level: s.level,
			 total: DsccView.bytes(s.totalBytes), used: DsccView.bytes(s.usedBytes), usedPct: DsccView.pct(s.usedBytes, s.totalBytes),
			 luns: lunsPerArray[key], url: s.url, note: s.note]
		}.sort { it.name?.toString()?.toLowerCase() }

		// Checks: LUNs some hosts don't see, and HPE LUNs DSCC doesn't list.
		List<Map> partial = luns.values().findAll { (seen[it.lunId as String] ?: 0) in 1..<hostCount }.collect { Map l ->
			l + [seenOn: "${seen[l.lunId as String]} of ${hostCount}".toString(), usedBy: (users[l.lunId as String] ?: [])*.text.take(2).join(', ') ?: UNUSED]
		}
		// Array LUNs the hosts see that Morpheus links to no VM disk or datastore (of this tenant): to review, not proof of disuse.
		Set<String> onHosts = hosts.collectMany { h -> h.luns*.deviceId.collect { it as String } } as Set
		List<Map> unclaimed = luns.values().findAll { it.matched && (it.lunId as String) in onHosts && !users[it.lunId as String] }.collect { Map l ->
			l + [seenOn: "${seen[l.lunId as String] ?: 0} of ${hostCount}".toString()]
		}.sort { it.volume as String }
		List<Map> unmatched = luns.values().findAll { !it.matched }.collect { Map l ->
			l + [usedBy: (users[l.lunId as String] ?: [])*.text.take(2).join(', ') ?: UNUSED,
			     seenOn: "${seen[l.lunId as String] ?: 0} of ${hostCount}".toString()]
		}

		return [state: luns ? 'cluster' : 'noLuns', clusterName: clusterName, arrays: arrays, datastores: dsRows, integrated: intRows,
		        partial: partial.take(50), partialCount: partial.size(), partialFew: partial.size() <= 5, unmatched: unmatched.take(50), unmatchedCount: unmatched.size(),
		        unmatchedMore: unmatched.size() > 50,
		        unclaimed: unclaimed.take(100), unclaimedCount: unclaimed.size(), unclaimedMore: unclaimed.size() > 100,
		        totals: [hosts: hostCount, luns: luns.size(), matched: luns.values().count { it.matched }, arrays: arrays.size(),
		                 arraysAttention: arrays.count { it.level in ['warn', 'bad'] }, datastores: dsRows.size(),
		                 integratedDisks: intRows.sum(0) { it.disks as int }]]
	}

	// ---- helpers -----------------------------------------------------------------------------------

	static Map<String, Integer> seenCounts(List<HostFacts> hosts) {
		Map<String, Integer> seen = [:].withDefault { 0 }
		(hosts ?: []).each { h ->
			Set<String> once = new HashSet<>()   // each host counts a LUN once (Groovy's unique() is quadratic)
			h.luns.each { Map l -> String id = l.deviceId as String; if (once.add(id)) seen[id]++ }
		}
		return seen
	}

	/** The first row per LUN id, in order (vmRows can be long on a busy host). */
	static List<Map> firstPerLun(List<Map> rows) {
		Map<String, Map> out = new LinkedHashMap<>()
		rows.each { Map r -> out.putIfAbsent(r.lunId as String, r) }
		return new ArrayList<Map>(out.values())
	}

	static Map totals(List<Map> rows) {
		[luns: rows.size(), matched: rows.count { it.matched },
		 attention: rows.count { it.matched && (it.statusLevel in ['warn', 'bad'] || it.arrayLevel in ['warn', 'bad']) }]
	}

	private static int rank(Map r) { r.usedByKind == 'datastore' ? 0 : (r.usedByKind == 'vm' ? 1 : 2) }
}
