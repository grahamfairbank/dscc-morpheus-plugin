package io.github.grahamfairbank.dsccmorpheus

/**
 * What the tabs need from Morpheus, copied out of its model classes by MorpheusReader so the storage map and the
 * service can be unit tested without Morpheus.
 */
class HostFacts {
	Long id
	String name
	String typeCode
	Boolean vmHypervisor
	Long clusterId
	/** SAN LUNs this host sees: [deviceId: normalized id, device: '/dev/mapper/...', sizeBytes:] */
	List<Map> luns = []
	/** Every device name Morpheus lists for the host (diagnostics). */
	List<String> devices = []
}

class DatastoreFacts {
	Long id
	String name
	String typeCode
	/** The LUN behind a block datastore (GFS2: config.blockDevice), normalized; null for other datastores. */
	String lunId
	Long sizeBytes
	Long freeBytes
	List<Long> hostIds = []
}

class DiskFacts {
	Long id
	String name
	String device
	Long sizeBytes
	/** The disk's own LUN (integrated LUN disks: wwn, or externalId /dev/mapper/...), normalized. */
	String lunId
	Long datastoreId
	String datastoreName
	/** What Morpheus gave for this disk (diagnostics). */
	String seen
}

class GuestFacts {
	Long id
	String name
	Long hostId
	/** Powered on. Morpheus 9.0.1 doesn't list a running VM's integration LUNs on its host, so the host tab adds them. */
	Boolean running
	List<DiskFacts> disks = []
}
