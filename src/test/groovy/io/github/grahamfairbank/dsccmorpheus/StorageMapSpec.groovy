package io.github.grahamfairbank.dsccmorpheus

import spock.lang.Specification

/** The index (units, health) and the three tab models over the synthetic cluster. */
class StorageMapSpec extends Specification {

	FakeDscc fake = new FakeDscc()

	DsccIndex index() { DsccIndex.build(new DsccClient(Fixtures.settings(), fake, { 1_000L }), 1_000L) }

	def "index: units, health and links per family"() {
		when:
		DsccIndex idx = index()
		ArraySystem mp = idx.systems["dt4:${Fixtures.MP_ID}".toString()]
		ArraySystem nim = idx.systems["dt2:${Fixtures.NIMBLE_ID}".toString()]
		ArrayVolume ds = idx.volume(WwnMatcher.norm(Fixtures.WWN_DS))
		ArrayVolume nv = idx.volume(Fixtures.NIMBLE_SERIAL)

		then:
		idx.systems.size() == 2
		idx.volumes.size() == 6
		idx.volume('60002ac000000000000000100001b000').with { sizeBytes == 2L * 1024 * 1024 * 1024 * 1024 && usedBytes == (long) (146266.5d * 1024 * 1024) }
		mp.state == 'Degraded' && mp.level == 'warn'          // overallState wins over state.overall
		mp.totalBytes == 10L * 1024 * 1024 * 1024 * 1024        // capacity-summary is MiB
		mp.usedBytes == 6L * 1024 * 1024 * 1024 * 1024
		mp.url == "https://console-us1.data.cloud.hpe.com/data-ops-manager/storage-systems/device-type4/${Fixtures.MP_ID}"
		ds.sizeBytes == 2L * 1024 * 1024 * 1024 * 1024 && ds.usedBytes == 512L * 1024 * 1024 * 1024
		ds.level == 'ok' && ds.state == 'Normal'
		nim.state == 'Healthy' && nim.model == '6010-QP-23T' && nim.totalBytes == 10995116277760L
		nv.sizeBytes == 2L * 1024 * 1024 * 1024 * 1024          // device-type2 volume size is MiB
		nv.usedBytes == 549755813888L                           // usage is bytes
		nv.url.endsWith("/block/volumes/device-type2/n1?systemUid=${Fixtures.NIMBLE_ID}")
	}

	def "a Nimble health fault shows as attention"() {
		given:
		fake.nimbleHealthy = false

		expect:
		index().systems["dt2:${Fixtures.NIMBLE_ID}".toString()].with { level == 'warn' && state.contains('SM_fan_fault') }
	}

	def "cluster: arrays, GFS2 datastores, integrated LUNs and the two checks"() {
		when:
		Map m = StorageMap.cluster('cl-1', Fixtures.hosts(), Fixtures.datastores(), Fixtures.guests(), index())

		then:
		m.state == 'cluster'
		m.totals.luns == 6 && m.totals.matched == 5 && m.totals.arrays == 2 && m.totals.arraysAttention == 1
		m.arrays*.name == ['mp-array-a', 'nimble-grp-a']
		m.arrays[0].luns == 4 && m.arrays[0].reduction == '3.3:1' && m.arrays[1].reduction == '25.7:1' && m.arrays[0].used == '6 TiB' && m.arrays[0].total == '10 TiB' && m.arrays[0].usedPct == 60
		m.datastores*.datastore == ['mp-ds01', 'nimble-ds01']
		m.datastores.every { it.matched && it.seenOn == '2 of 2' }
		m.datastores[1].volume == 'nimble-ds01' && m.datastores[1].array == 'nimble-grp-a'
		m.integrated.size() == 1
		m.integrated[0].with { datastore == 'Alletra MP integrated' && disks == 2 && vms == 2 && matched == 2 && arrays == 'mp-array-a' }
		m.partialCount == 1 && m.partial[0].lunId == Fixtures.WWN_SNAP && m.partial[0].seenOn == '1 of 2'
		m.unmatchedCount == 1 && m.unmatched[0].lunId == Fixtures.WWN_SNAP
		m.unmatched[0].statusText.contains('snapshot')
		m.unclaimedCount == 1 && m.unclaimed[0].volume == 'unused-vol' && m.unclaimed[0].seenOn == '2 of 2'
	}

	def "host: every array LUN it sees, with who uses it; local disks only counted"() {
		when:
		List<HostFacts> hosts = Fixtures.hosts()
		Map m = StorageMap.host(hosts[0], hosts, Fixtures.datastores(), Fixtures.guests(), index())

		then:
		m.state == 'host'
		m.rows.size() == 5
		m.otherLuns == 1
		m.rows[0].usedBy.startsWith('Datastore ')            // datastores first
		m.rows.find { it.lunId == WwnMatcher.norm(Fixtures.WWN_VM1) }.usedBy == 'VM vm-a: data-1'
		m.rows.find { it.lunId == Fixtures.WWN_SNAP }.with { !matched && seenLevel == 'warn' && usedBy == StorageMap.UNUSED }
		m.rows.find { it.lunId == WwnMatcher.norm(Fixtures.WWN_LEFTOVER) }.with { matched && usedBy == StorageMap.UNUSED }
		m.totals.luns == 5 && m.totals.matched == 4 && m.totals.vmDisks == 0 && m.totals.unused == 1
	}

	def "host: a running VM's integration LUN, not listed on the host, is added from the VM"() {
		when:
		List<HostFacts> hosts = Fixtures.hosts()
		Map m = StorageMap.host(hosts[1], hosts, Fixtures.datastores(), Fixtures.guests(), index())
		Map vm = m.rows.find { it.lunId == WwnMatcher.norm(Fixtures.WWN_VM2) }

		then:
		vm.usedBy == 'VM vm-b: root' && vm.seenOn == 'VM runs here' && vm.vmDisk && vm.volume == 'HPE_VM_vm-b-root'
		m.totals.vmDisks == 1
	}

	def "host: a stopped VM's disks are not added"() {
		given:
		List<GuestFacts> guests = Fixtures.guests()
		guests[1].running = false

		expect:
		StorageMap.host(Fixtures.hosts()[1], Fixtures.hosts(), Fixtures.datastores(), guests, index()).totals.vmDisks == 0
	}

	def "guest: a file on a GFS2 datastore follows the datastore's LUN, not a stale disk wwn"() {
		given:
		Map<Long, DatastoreFacts> ds = Fixtures.datastores().collectEntries { [(it.id): it] }

		when:
		Map m = StorageMap.guest([Fixtures.guests()[0]], ds, index())

		then:
		m.state == 'guest'
		m.rows.size() == 3
		m.rows[0].with { kind == 'file' && lunId == Fixtures.NIMBLE_SERIAL && array == 'nimble-grp-a' && kindText == 'File on GFS2 datastore nimble-ds01' }
		m.rows[1].with { kind == 'lun' && volume == 'HPE_VM_vm-a-data' && array == 'mp-array-a' && arrayLevel == 'warn' && kindText == 'Alletra B10000 Integration LUN (Alletra MP integrated)' }
		m.rows[2].with { kind == 'other' && !matched }
		m.totals.luns == 2 && m.totals.matched == 2
	}

	def "guest and host with nothing on an array say so"() {
		expect:
		StorageMap.guest([new GuestFacts(id: 1L, name: 'x', disks: [new DiskFacts(name: 'root', datastoreId: 14L)])],
			[14L: Fixtures.datastores()[3]], index()).state == 'noLuns'
		StorageMap.host(new HostFacts(name: 'h', luns: [[deviceId: WwnMatcher.norm(Fixtures.LOCAL)]]), [], [], [], index()).state == 'noLuns'
	}
}
