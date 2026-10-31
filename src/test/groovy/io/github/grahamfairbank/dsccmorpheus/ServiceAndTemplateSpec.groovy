package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import spock.lang.Specification

/** The service's states, and every template rendered the way Morpheus does. */
class ServiceAndTemplateSpec extends Specification {

	HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
	FakeDscc fake = new FakeDscc()
	long now = 1_000L
	DsccService service = new DsccService({ DsccSettings s -> fake }, { now }, new DirectExecutor())
	Closure<Map> clusterFacts = { [hosts: Fixtures.hosts(), datastores: Fixtures.datastores(), guests: Fixtures.guests()] }

	private String render(String template, Map model) {
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = DsccView.withFlags(model)
		return renderer.renderTemplate("hbs/${template}", vm).html
	}

	private static String decoded(String html) { html.replace('&#x3D;', '=').replace('&amp;', '&').replace('&#x2F;', '/') }

	def "unconfigured, then a first read, then data"() {
		expect:
		service.clusterTab('cl', clusterFacts, DsccSettings.fromMap([:])).state == 'unconfigured'

		when:
		Map m = service.clusterTab('cl', clusterFacts, Fixtures.settings())

		then: 'the direct executor finished the background read, but this render started before it'
		m.state == 'building'
		service.clusterTab('cl', clusterFacts, Fixtures.settings()).state == 'cluster'
	}

	def "an index read failure is shown, and retried after a minute"() {
		given:
		fake.legacyOnly = true
		DsccSettings s = Fixtures.settings()

		when:
		service.clusterTab('cl', clusterFacts, s)
		Map m = service.clusterTab('cl', clusterFacts, s)

		then:
		m.state == 'error'
		m.error.contains('authorized no access')
		int posts = fake.calls.count { it.startsWith('POST') }

		when:
		fake.legacyOnly = false
		now += 61_000L
		service.clusterTab('cl', clusterFacts, s)

		then:
		fake.calls.count { it.startsWith('POST') } > posts
		service.clusterTab('cl', clusterFacts, s).state == 'cluster'
	}

	def "cluster template: chips, arrays with links, datastores, integrated, checks"() {
		given:
		service.clusterTab('cl', clusterFacts, Fixtures.settings())

		when:
		String html = decoded(render('dscc-cluster', service.clusterTab('cl', clusterFacts, Fixtures.settings())))

		then:
		html.contains('Array LUNs: 6 (5 in DSCC)')
		html.contains('Array LUNs on the hosts that Morpheus doesn') && html.contains("t link to a VM or datastore (1)")
		html.contains('Arrays needing attention: 1')
		html.contains("href=\"https://console-us1.data.cloud.hpe.com/data-ops-manager/storage-systems/device-type4/${Fixtures.MP_ID}\"")
		html.contains('6 TiB of 10 TiB (60%)')
		html.contains('>mp-ds01<') && html.contains('>nimble-ds01<')
		html.contains('HPE Alletra B10000 Integration')
		!html.contains('<th>Family</th>')
		html.contains('3.3:1') && html.contains('25.7:1')
		html.contains("data-dscc-copy=\"${WwnMatcher.norm(Fixtures.WWN_DS)}\"")
		html.contains('Array LUNs not seen by every host')
		html.contains("HPE array LUNs that DSCC doesn\'t list as volumes") || html.contains('HPE array LUNs that DSCC doesn&#x27;t list as volumes')
		html.contains('unofficial community plugin')
	}

	def "host and guest templates"() {
		given:
		DsccSettings s = Fixtures.settings()
		List<HostFacts> hosts = Fixtures.hosts()
		Closure<Map> hostFacts = { [host: hosts[0], hosts: hosts, datastores: Fixtures.datastores(), guests: Fixtures.guests()] }
		Closure<Map> guestFacts = { [guests: [Fixtures.guests()[0]], datastores: Fixtures.datastores().collectEntries { [(it.id): it] }] }
		service.hostTab('host-a', hostFacts, s)

		when:
		String host = decoded(render('dscc-host', service.hostTab('host-a', hostFacts, s)))
		String guest = decoded(render('dscc-guest', service.guestTab('vm-a', guestFacts, s)))

		then:
		host.contains('Array LUNs on this host: 5 (4 in DSCC)')
		host.contains('VM vm-a: data-1')
		host.contains('1 of 2')
		host.contains('Not linked in Morpheus: 1') && host.contains('Not linked to a VM or datastore in Morpheus')
		host.contains('check in DSCC before reclaiming anything')
		guest.contains('File on GFS2 datastore nimble-ds01')
		guest.contains('HPE_VM_vm-a-data')
		guest.contains('/block/volumes/device-type4/v2?systemUid=')
		!guest.contains('<th>VM</th>')
	}

	def "every state renders its explanation"() {
		expect:
		['dscc-cluster', 'dscc-host', 'dscc-guest'].every { render(it, model).contains(text) }

		where:
		model                                           | text
		[state: 'unconfigured']                         | 'has no DSCC API client yet'
		[state: 'building']                             | 'Reopen this tab shortly'
		[state: 'error', error: 'Couldn\'t sign in']    | 'Couldn&#x27;t sign in'
	}

	def "a VM with no array disk shows what the plugin saw"() {
		given:
		DsccSettings st = Fixtures.settings()
		Closure<Map> f = { [guests: [new GuestFacts(id: 1L, name: 'vm', disks: [new DiskFacts(name: 'root', device: '/dev/vda', seen: 'wwn empty, externalId empty, type standard', datastoreId: 14L)])],
		                    datastores: [14L: Fixtures.datastores()[3]]] }
		service.guestTab('vm', f, st)

		when:
		String html = decoded(render('dscc-guest', service.guestTab('vm', f, st)))

		then:
		html.contains("None of this VM") && html.contains('wwn empty, externalId empty, type standard; datastore local (libvirt-dir)')
	}

	def "values are HTML-escaped"() {
		expect:
		!render('dscc-host', [state: 'error', error: '<script>alert(1)</script>']).contains('<script>alert')
	}
}
