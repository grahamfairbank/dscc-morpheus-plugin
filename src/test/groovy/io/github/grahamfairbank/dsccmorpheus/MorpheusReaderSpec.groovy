package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.DatastoreLocation
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.projection.DatastoreIdentityProjection
import spock.lang.Specification

/** Morpheus model objects in the shapes seen on 9.0.1, read into facts (no services needed). */
class MorpheusReaderSpec extends Specification {

	MorpheusReader reader = new MorpheusReader(null)

	def "host: multipath devices and WWNs become LUN ids, plain device names don't"() {
		given:
		ComputeServer s = new ComputeServer(id: 1L, name: 'host-a', computeServerType: new ComputeServerType(code: 'mvmHost', vmHypervisor: true))
		s.volumes = [
			new StorageVolume(name: 'disk-sdb', deviceName: '/dev/sdb', wwn: Fixtures.LOCAL, maxStorage: 1L),
			new StorageVolume(name: 'disk-3x', deviceName: "/dev/mapper/3${Fixtures.WWN_DS.toLowerCase()}".toString(), maxStorage: 2L),
			new StorageVolume(name: 'disk-2x', deviceName: "/dev/mapper/2${Fixtures.NIMBLE_SERIAL}".toString(), maxStorage: 3L),
			new StorageVolume(name: 'disk-sda', deviceName: '/dev/sda')]

		when:
		HostFacts h = reader.host(s)

		then:
		h.luns*.deviceId == [WwnMatcher.norm(Fixtures.LOCAL), WwnMatcher.norm(Fixtures.WWN_DS), Fixtures.NIMBLE_SERIAL]
	}

	def "GFS2 datastore: LUN from config.blockDevice; hosts from locations"() {
		given:
		Datastore d = new Datastore(id: 11L, name: 'nimble-ds01', storageSize: 10L, freeSpace: 4L)
		d.config = '{"blockDevice":"/dev/mapper/2' + Fixtures.NIMBLE_SERIAL + '"}'
		d.locations = [new DatastoreLocation(refType: 'ComputeServer', refId: 1L), new DatastoreLocation(refType: 'ComputeServer', refId: 2L)]

		when:
		DatastoreFacts f = MorpheusReader.datastore(d)

		then:
		f.lunId == Fixtures.NIMBLE_SERIAL
		f.hostIds == [1L, 2L]
		MorpheusReader.datastore(new Datastore(id: 1L, name: 'local')).lunId == null
	}

	def "VM disks: integrated LUN by wwn or by externalId; a file has neither"() {
		given:
		DatastoreIdentityProjection ds = new DatastoreIdentityProjection()
		ds.id = 13L
		ds.name = 'Alletra MP integrated'

		expect:
		MorpheusReader.disk(new StorageVolume(name: 'root', wwn: Fixtures.WWN_VM2.toLowerCase(), datastore: ds)).with { lunId == WwnMatcher.norm(Fixtures.WWN_VM2) && datastoreId == 13L }
		MorpheusReader.disk(new StorageVolume(name: 'root', externalId: "/dev/mapper/3${Fixtures.WWN_VM1.toLowerCase()}".toString())).lunId == WwnMatcher.norm(Fixtures.WWN_VM1)
		MorpheusReader.disk(new StorageVolume(name: 'data', externalId: 'CT-SQL/hvm_1-disk-1')).lunId == null
	}

	def "a shallow server or instance is loaded in full"() {
		given:
		ComputeServer fullHost = new ComputeServer(id: 5L, name: 'host-a', computeServerType: new ComputeServerType(code: 'mvmHost', vmHypervisor: true),
			volumes: [new StorageVolume(deviceName: "/dev/mapper/3${Fixtures.WWN_DS.toLowerCase()}".toString())])
		def servers = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService) { get(5L) >> fullHost }
		def workloads = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousWorkloadService) {
			list(_) >> [new com.morpheusdata.model.Workload(server: new ComputeServer(id: 5L))]
		}
		def services = Mock(com.morpheusdata.core.MorpheusServices) { getComputeServer() >> servers; getWorkload() >> workloads }
		MorpheusReader r = new MorpheusReader(Mock(com.morpheusdata.core.MorpheusContext) { getServices() >> services })

		expect:
		MorpheusReader.isHost(r.full(new ComputeServer(id: 5L)))
		r.host(r.full(new ComputeServer(id: 5L))).luns.size() == 1
		r.instanceServers(new com.morpheusdata.model.Instance(id: 9L))*.name == ['host-a']
	}

	def "owner: the server's account, else its cluster's owner"() {
		given:
		def acct = new com.morpheusdata.model.Account(id: 3L)
		def cl = new com.morpheusdata.model.ComputeServerGroup(id: 7L, owner: acct)

		expect:
		MorpheusReader.ownerOf(new ComputeServer(id: 1L, account: new com.morpheusdata.model.Account(id: 4L), serverGroup: cl)) == 4L
		MorpheusReader.ownerOf(new ComputeServer(id: 1L, serverGroup: cl)) == 3L
		MorpheusReader.ownerOf(new ComputeServer(id: 1L)) == null
		MorpheusReader.mayView(3L, acct)
		!MorpheusReader.mayView(4L, acct)
		// Morpheus 9.0.1 passes a null account to server tab checks: the user's own account is used instead
		MorpheusReader.mayView(3L, MorpheusReader.viewer(new com.morpheusdata.model.User(account: acct), null))
		MorpheusReader.viewer(null, null) == null
		MorpheusReader.mayViewPage(3L, null)          // no viewer at all: the tab follows the page
		!MorpheusReader.mayViewPage(4L, acct)         // a known viewer of another tenant: hidden
	}

	def "tenancy fails closed"() {
		expect:
		MorpheusReader.owned([new ComputeServer(id: 1L)], null) == []
		!MorpheusReader.mayView(null, null)
	}
}
