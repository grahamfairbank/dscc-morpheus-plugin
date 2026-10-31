package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.DatastoreLocation
import com.morpheusdata.model.Instance
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.User
import com.morpheusdata.model.Workload
import groovy.util.logging.Slf4j

/**
 * Reads hosts, datastores and VM disks from Morpheus into plain facts. Read-only.
 *
 * Shapes measured on Morpheus 9.0.1 (2026-10-29):
 * - a hypervisor host's volumes include one per multipath LUN, deviceName /dev/mapper/&lt;scsi_id&gt;, no wwn;
 * - a cluster's datastores have refType ComputeServerGroup and refId = the cluster id; GFS2 ones keep the LUN in
 *   config.blockDevice;
 * - an integrated Alletra MP VM disk has type hpealletraMPLUN, wwn set and externalId /dev/mapper/3&lt;wwn&gt;.
 */
@Slf4j
class MorpheusReader {

	final MorpheusContext morpheus

	MorpheusReader(MorpheusContext morpheus) { this.morpheus = morpheus }

	// ---- hosts -------------------------------------------------------------------------------------

	/**
	 * The full server record, always loaded by id. Tab providers are handed a server whose volumes can lack the
	 * fields the plugin needs (seen on 9.0.1: no HPE Storage tab on hosts or VMs while the cluster tab, built
	 * from list() records, worked), so the tab never trusts the object it was given.
	 */
	ComputeServer full(ComputeServer s) {
		if (s?.id == null) return s
		return load(s.id) ?: s
	}

	/** The server by id, or null when it can't be loaded. */
	ComputeServer load(Long id) {
		try {
			return morpheus.services.computeServer.get(id)
		} catch (Throwable e) {
			log.warn("dscc-morpheus: could not load server ${id}: ${e}")
			return null
		}
	}

	/** Owner tenant of a server: its account, else its cluster's owner. */
	static Long ownerOf(ComputeServer s) {
		return (safe { s?.account?.id } ?: safe { s?.serverGroup?.owner?.id }) as Long
	}

	/** The full instance record (a tab can be handed a bare one), loaded by id. */
	Instance fullInstance(Instance i) {
		if (i?.id == null) return i
		return loadInstance(i.id) ?: i
	}

	/** The instance by id, or null when it can't be loaded. */
	Instance loadInstance(Long id) {
		try {
			return morpheus.services.instance.get(id)
		} catch (Throwable e) {
			log.warn("dscc-morpheus: could not load instance ${id}: ${e}")
			return null
		}
	}

	static boolean isHost(ComputeServer s) {
		return s?.computeServerType?.vmHypervisor == true || s?.computeServerType?.code in ['mvmHost', 'kvmHost']
	}

	/**
	 * A volume with the fields the plugin reads; loads it by id only when it looks like a shallow projection (no
	 * type and none of those fields). Records from get() and list() are complete (measured on 9.0.1: reloading a
	 * bare local or datastore-less disk never added anything), so they aren't reloaded.
	 */
	StorageVolume fullVolume(StorageVolume v) {
		if (v?.id == null) return v
		boolean bare = !v.wwn && !v.externalId && !(v.deviceName?.startsWith('/dev/mapper/')) && v.datastore == null
		if (!bare || safe { v.type } != null) return v
		try {
			return morpheus.services.storageVolume.get(v.id) ?: v
		} catch (Throwable e) {
			log.debug("dscc-morpheus: could not load volume ${v.id}: ${e}")
			return v
		}
	}

	HostFacts host(ComputeServer s) {
		HostFacts h = new HostFacts(id: s.id, name: s.name, typeCode: s.computeServerType?.code,
			vmHypervisor: s.computeServerType?.vmHypervisor, clusterId: safe { s.serverGroup?.id } as Long)
		List<StorageVolume> vols = volumesOf(s)
		Map<String, Map> luns = new LinkedHashMap<>()   // first per device id, in volume order
		vols.collect { fullVolume(it) }.each { StorageVolume v ->
			String id = WwnMatcher.norm(v.deviceName?.startsWith('/dev/mapper/') ? v.deviceName : null) ?: WwnMatcher.norm(v.wwn)
			if (id && !luns.containsKey(id)) luns[id] = [deviceId: id, device: v.deviceName ?: v.name, sizeBytes: v.maxStorage]
		}
		h.luns = new ArrayList<Map>(luns.values())
		h.devices = new ArrayList<String>(new LinkedHashSet<String>(vols.collect { (it.deviceName ?: it.name ?: '?') as String }))
		return h
	}

	/** The cluster's hypervisor hosts that belong to the cluster's own tenant (render has no user: the owner decides). */
	List<ComputeServer> clusterHosts(ComputeServerGroup cluster) {
		List<ComputeServer> all = morpheus.services.computeServer.list(new DataQuery().withFilter('serverGroup.id', cluster.id)) ?: []
		return owned(all, safe { cluster?.owner?.id } as Long).findAll { isHost(it) }
	}

	/** Keeps servers whose tenant is the owner. With no known owner, keeps none (fail closed). */
	static List<ComputeServer> owned(List<ComputeServer> servers, Long owner) {
		if (owner == null) return []
		return (servers ?: []).findAll { ComputeServer c -> (safe { c.account?.id } as Long) == owner }
	}

	// ---- datastores --------------------------------------------------------------------------------

	List<DatastoreFacts> clusterDatastores(Long clusterId) {
		if (clusterId == null) return []
		List<Datastore> list = morpheus.services.cloud.datastore.list(new DataQuery()
			.withFilter('refType', 'ComputeServerGroup').withFilter('refId', clusterId)) ?: []
		return list.findAll { it.active != false }.collect { datastore(it) }
	}

	DatastoreFacts datastoreById(Long id) {
		if (id == null) return null
		Datastore d = morpheus.services.cloud.datastore.get(id)
		return d ? datastore(d) : null
	}

	static DatastoreFacts datastore(Datastore d) {
		Map cfg = (safe { d.configMap } ?: [:]) as Map
		List<Long> hostIds = []
		try {
			(d.locations ?: []).each { DatastoreLocation l -> if (l?.refType in [null, 'ComputeServer'] && l?.refId) hostIds << l.refId }
		} catch (Throwable ignored) { }
		return new DatastoreFacts(id: d.id, name: d.name, typeCode: safe { d.datastoreType?.code } as String ?: d.type,
			lunId: WwnMatcher.norm(cfg.blockDevice), sizeBytes: d.storageSize, freeBytes: d.freeSpace, hostIds: hostIds.unique())
	}

	// ---- guests (VMs) ------------------------------------------------------------------------------

	GuestFacts guest(ComputeServer s) {
		GuestFacts g = new GuestFacts(id: s.id, name: s.name, hostId: safe { s.parentServer?.id } as Long,
			running: (safe { s.powerState?.toString()?.toLowerCase() } as String) == 'on')
		g.disks = volumesOf(s).collect { StorageVolume v -> disk(fullVolume(v)) }
		return g
	}

	static DiskFacts disk(StorageVolume v) {
		String own = WwnMatcher.norm(v.wwn) ?: WwnMatcher.norm(v.externalId?.startsWith('/dev/mapper/') ? v.externalId : null)
		return new DiskFacts(id: v.id, name: v.name, device: v.deviceName, sizeBytes: v.maxStorage, lunId: own,
			datastoreId: safe { v.datastore?.id } as Long, datastoreName: safe { v.datastore?.name } as String,
			seen: "wwn ${v.wwn ? 'set' : 'empty'}, externalId ${v.externalId ?: 'empty'}, type ${safe { v.type?.code } ?: '?'}".toString())
	}

	/** The VMs running on these hosts, in the hosts' tenant (fail closed without one). */
	List<ComputeServer> guestsOn(List<Long> hostIds, Long owner) {
		if (!hostIds) return []
		List<ComputeServer> all = morpheus.services.computeServer.list(new DataQuery()
			.withFilter(new DataFilter('parentServer.id', 'in', hostIds))) ?: []
		return owned(all, owner).findAll { !isHost(it) }
	}

	/** The VM servers behind an instance. */
	List<ComputeServer> instanceServers(Instance instance) {
		List<ComputeServer> out = []
		try {
			(instance?.containers ?: []).each { Workload w -> if (w?.server?.id) out << w.server }
		} catch (Throwable e) {
			log.debug("dscc-morpheus: could not read workloads of instance ${instance?.id}", e)
		}
		if (!out && instance?.id != null) {
			// A shallow instance carries no workloads: look them up.
			try {
				(morpheus.services.workload.list(new DataQuery().withFilter('instance.id', instance.id)) ?: []).each { Workload w ->
					if (w?.server?.id) out << w.server
				}
			} catch (Throwable e) {
				log.warn("dscc-morpheus: could not list workloads of instance ${instance.id}: ${e}")
			}
		}
		out = out.collect { ComputeServer s -> full(s) }
		return out.unique { it.id }
	}

	/**
	 * A server's volumes. Loads the server again only when the object has no volume list, or an empty one while
	 * the server itself looks shallow (no type: plugin-core starts volumes as an empty list). A full record with no
	 * volumes is taken as it is.
	 */
	List<StorageVolume> volumesOf(ComputeServer s) {
		List<StorageVolume> list = s?.volumes
		boolean shallow = list == null || (list.isEmpty() && safe { s.computeServerType } == null)
		if (shallow && s?.id != null) {
			try {
				list = morpheus.services.computeServer.get(s.id)?.volumes
			} catch (Throwable e) {
				log.warn("dscc-morpheus: could not load volumes for server ${s.id}: ${e}")
			}
		}
		return list ?: []
	}

	// ---- tenancy -----------------------------------------------------------------------------------

	/** The viewer's tenant: the account Morpheus passes, else the user's own (9.0.1 passes null to server tabs). */
	static Account viewer(User user, Account account) {
		return account ?: (safe { user?.account } as Account)
	}

	/**
	 * For tab checks: with a known viewer, the tenant rule below. With no viewer at all (Morpheus 9.0.1 passes
	 * neither an account nor a user account to server and instance tab checks), the page itself is already
	 * limited by Morpheus to users who may see that server or instance, so the tab follows the page.
	 */
	static boolean mayViewPage(Long ownerId, Account viewer) {
		return viewer == null ? true : mayView(ownerId, viewer)
	}

	/** The user's tenant owns it, or the user is in the master tenant. */
	static boolean mayView(Long ownerId, Account account) {
		if ((safe { account?.masterAccount }) == true) return true
		Long mine = safe { account?.id } as Long
		return mine != null && mine == ownerId
	}

	static Object safe(Closure c) {
		try { return c.call() } catch (Throwable ignored) { return null }
	}
}
