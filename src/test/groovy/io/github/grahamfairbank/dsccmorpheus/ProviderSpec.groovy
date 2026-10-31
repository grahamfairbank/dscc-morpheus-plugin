package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.MorpheusSynchronousInstanceService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Instance
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.DatastoreIdentityProjection
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import spock.lang.Specification

/**
 * The tab providers on a mocked Morpheus: show() and the render right after it share their loads, the tenant
 * check still runs on every show(), the cluster check doesn't read the VMs, and a passing check starts the index.
 */
class ProviderSpec extends Specification {

	FakeDscc fake = new FakeDscc()
	long now = 1_000L
	DsccSettings settings = Fixtures.settings()

	Account tenant = new Account(id: 3L)
	Account other = new Account(id: 4L)
	ComputeServerGroup cluster = new ComputeServerGroup(id: 7L, name: 'cl-1', owner: tenant)
	String dsLun = "/dev/mapper/3${Fixtures.WWN_DS.toLowerCase()}".toString()
	ComputeServer host = new ComputeServer(id: 1L, name: 'host-a', account: tenant, serverGroup: cluster,
		computeServerType: new ComputeServerType(code: 'mvmHost', vmHypervisor: true),
		volumes: [new StorageVolume(id: 50L, name: 'lun', deviceName: dsLun, maxStorage: 2L)])
	Datastore gfs2 = new Datastore(id: 12L, name: 'mp-ds01', config: '{"blockDevice":"' + dsLun + '"}', storageSize: 10L, freeSpace: 4L)
	ComputeServer vm = new ComputeServer(id: 101L, name: 'vm-a', account: tenant, parentServer: host,
		computeServerType: new ComputeServerType(code: 'mvmVm', vmHypervisor: false),
		volumes: [new StorageVolume(id: 60L, name: 'root', deviceName: '/dev/vda', datastore: projection(12L, 'mp-ds01'))])
	Instance instance = new Instance(id: 9L, name: 'inst-a', account: tenant, containers: [new Workload(server: new ComputeServer(id: 101L))])

	int serverGets = 0, instanceGets = 0, hostLists = 0, vmLists = 0
	/** 'null' or 'throw' to make computeServer.get fail. */
	String serverGetFails = null

	MorpheusContext ctx = Mock(MorpheusContext) {
		getServices() >> Mock(MorpheusServices) {
			getComputeServer() >> Mock(MorpheusSynchronousComputeServerService) {
				get(_) >> { Long id ->
					serverGets++
					if (serverGetFails == 'throw') throw new IllegalStateException('morpheus down')
					serverGetFails == 'null' ? null : [1L: host, 101L: vm][id]
				}
				list(_) >> { DataQuery q ->
					if (q.filters*.name.contains('parentServer.id')) { vmLists++; return [vm] }
					hostLists++
					return [host]
				}
			}
			getInstance() >> Mock(MorpheusSynchronousInstanceService) { get(9L) >> { instanceGets++; instance } }
			getCloud() >> Mock(MorpheusSynchronousCloudService) {
				getDatastore() >> Mock(MorpheusSynchronousDatastoreService) { list(_) >> [gfs2]; get(12L) >> gfs2 }
			}
			getStorageVolume() >> Mock(MorpheusSynchronousStorageVolumeService)
		}
	}

	DsccMorpheusPlugin plugin = new TestPlugin(this)

	private static DatastoreIdentityProjection projection(Long id, String name) {
		DatastoreIdentityProjection p = new DatastoreIdentityProjection()
		p.id = id
		p.name = name
		return p
	}

	def setup() {
		plugin.service = new DsccService({ DsccSettings s -> fake }, { now }, new DirectExecutor(), new DirectExecutor())
	}

	private DsccServerTabProvider serverTab() {
		new DsccServerTabProvider(plugin, ctx) { @Override Renderer<?> getRenderer() { new HandlebarsRenderer('renderer', DsccMorpheusPlugin.classLoader) } }
	}

	private DsccInstanceTabProvider instanceTab() {
		new DsccInstanceTabProvider(plugin, ctx) { @Override Renderer<?> getRenderer() { new HandlebarsRenderer('renderer', DsccMorpheusPlugin.classLoader) } }
	}

	private DsccClusterTabProvider clusterTab() {
		new DsccClusterTabProvider(plugin, ctx) { @Override Renderer<?> getRenderer() { new HandlebarsRenderer('renderer', DsccMorpheusPlugin.classLoader) } }
	}

	def "host page: show() and render load the server once; the tenant check runs on every show()"() {
		given:
		DsccServerTabProvider tab = serverTab()

		when:
		boolean mine = tab.show(new ComputeServer(id: 1L), null, tenant)
		boolean theirs = tab.show(new ComputeServer(id: 1L), null, other)
		String html = tab.renderTemplate(new ComputeServer(id: 1L)).html

		then: 'the other tenant is refused although the server is in the memo'
		mine
		!theirs
		serverGets == 1

		and: 'the first passing check started the index read, so the render has data'
		fake.calls.any { it.startsWith('GET') }
		html.contains('Array LUNs on this host')

		when: 'a later page view, after the page memo expired'
		now += DsccService.PAGE_TTL_MS + 1
		tab.show(new ComputeServer(id: 1L), null, tenant)

		then:
		serverGets == 2
	}

	def "a check that fails starts no index read"() {
		expect:
		!serverTab().show(new ComputeServer(id: 1L), null, other)
		!fake.calls
	}

	def "VM page: show() and render share the server and its disks"() {
		given:
		DsccServerTabProvider tab = serverTab()

		when:
		boolean shown = tab.show(new ComputeServer(id: 101L), null, tenant)
		String html = tab.renderTemplate(new ComputeServer(id: 101L)).html

		then:
		shown
		serverGets == 1
		html.contains('File on GFS2 datastore mp-ds01')
	}

	def "instance page: the instance and its servers are loaded once for show() and render"() {
		given:
		DsccInstanceTabProvider tab = instanceTab()

		when:
		boolean shown = tab.show(new Instance(id: 9L), null, tenant)
		boolean theirs = tab.show(new Instance(id: 9L), null, other)
		String html = tab.renderTemplate(new Instance(id: 9L, name: 'inst-a')).html

		then:
		shown
		!theirs
		instanceGets == 1
		serverGets == 1
		html.contains('File on GFS2 datastore mp-ds01')
	}

	def "cluster page: the check reads hosts and datastores only; the render adds the VMs once a minute"() {
		given:
		DsccClusterTabProvider tab = clusterTab()

		when:
		boolean shown = tab.show(cluster, null, tenant)

		then:
		shown
		hostLists == 1
		vmLists == 0

		when:
		String html = tab.renderTemplate(cluster).html
		tab.renderTemplate(cluster)

		then:
		html.contains('mp-ds01')
		hostLists == 1
		vmLists == 1

		and: 'another tenant gets no tab'
		!tab.show(cluster, null, other)

		when: 'past the TTL: read again'
		now += DsccService.FACTS_TTL_MS + 1
		tab.renderTemplate(cluster)

		then:
		hostLists == 2
		vmLists == 2
	}

	def "a cluster with no known owner gets no tab, also for a master-tenant viewer, and nothing is read"() {
		given:
		ComputeServerGroup ownerless = new ComputeServerGroup(id: 8L, name: 'cl-x')
		Account master = new Account(id: 1L, masterAccount: true)

		expect:
		!clusterTab().show(ownerless, null, tenant)
		!clusterTab().show(ownerless, null, master)
		hostLists == 0 && vmLists == 0
		plugin.clusterFacts(ownerless) == [hosts: [], datastores: [], guests: []]
	}

	def "when the server can't be loaded (#mode), show() decides on the passed object and fails closed"() {
		given:
		serverGetFails = mode
		DsccServerTabProvider tab = serverTab()

		expect: 'the shallow object has no owner: a known viewer is refused, and the failure is not kept'
		!tab.show(new ComputeServer(id: 1L), null, tenant)
		!tab.show(new ComputeServer(id: 1L), null, tenant)
		serverGets == 2
		!fake.calls

		where:
		mode << ['null', 'throw']
	}

	def "a new cluster owner is a new read, filtered by the new owner"() {
		given:
		Map first = plugin.clusterStorage(cluster)

		when:
		cluster.owner = other
		Map second = plugin.clusterStorage(cluster)

		then:
		hostLists == 2
		first.hosts*.id == [1L]
		second.hosts == []   // the host belongs to the old owner

		when: 'back to the first owner, within the minute: that read is reused'
		cluster.owner = tenant
		plugin.clusterStorage(cluster)

		then:
		hostLists == 2
	}

	def "host tab render reuses the cluster facts the cluster tab read"() {
		when:
		clusterTab().show(cluster, null, tenant)
		clusterTab().renderTemplate(cluster)
		serverTab().renderTemplate(new ComputeServer(id: 1L))

		then:
		hostLists == 1
		vmLists == 1
	}
}

/** The plugin with fixed settings and the mocked Morpheus. */
class TestPlugin extends DsccMorpheusPlugin {
	final ProviderSpec spec
	TestPlugin(ProviderSpec spec) { this.spec = spec }
	@Override DsccSettings loadSettings() { spec.settings }
	@Override MorpheusReader reader() { new MorpheusReader(spec.ctx) }
}
