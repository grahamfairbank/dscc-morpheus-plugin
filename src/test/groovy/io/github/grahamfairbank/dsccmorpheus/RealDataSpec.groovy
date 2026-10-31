package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import groovy.json.JsonSlurper
import spock.lang.IgnoreIf
import spock.lang.Specification

/**
 * Opt-in, local only: DSCC_FIXTURES=<folder> with one dscc-spike-* and one morpheus-lun-* folder from the spike
 * scripts. Builds the index from the saved DSCC responses and facts from the saved Morpheus API JSON, prints the
 * match counts, and with PREVIEW_OUT=<folder> writes the three tabs as HTML. Real data never goes into the repo.
 *
 * The Morpheus spike has no VM-to-disk links, so every storage volume becomes a disk of one pseudo VM.
 */
@IgnoreIf({ !System.getenv('DSCC_FIXTURES') })
class RealDataSpec extends Specification {

	File root = new File(System.getenv('DSCC_FIXTURES') ?: '.')
	File dscc = root.listFiles().find { it.name.startsWith('dscc-spike-') }
	File morph = root.listFiles().find { it.name.startsWith('morpheus-lun-') }
	JsonSlurper json = new JsonSlurper()

	/** Serves the saved DSCC files for the paths the client asks for. */
	DsccTransport saved = { String method, String url, Map h, String body ->
		if (method == 'POST') return new HttpResult(200, '{"access_token":"t","expires_in":899}')
		String p = new URI(url).path.replaceFirst('^/api/v1/storage-systems/?', '')
		String q = new URI(url).query ?: ''
		File f
		if (p ==~ /device-type\d/) f = new File(dscc, "systems-dt${p[-1]}.json")
		else {
			def m = p =~ /device-type(\d)\/([^\/]+)(?:\/(.*))?/
			if (!m.matches()) return new HttpResult(404, '{}')
			String suffix = m.group(3) == null ? '' : "-${m.group(3) == 'volumes' ? 'volumes-p0' : m.group(3).replace('capacity-summary', 'capacity').replace('health-status', 'health')}"
			if (m.group(3) == 'volumes' && !q.contains('offset=0')) return new HttpResult(200, '{"items":[],"total":0}')
			f = new File(dscc, "sys/dt${m.group(1)}-${m.group(2)}${suffix}.json")
		}
		return f.exists() ? new HttpResult(200, f.text) : new HttpResult(404, '{}')
	} as DsccTransport

	def "real data: index, match counts and the three tabs"() {
		given:
		DsccSettings s = DsccSettings.fromMap([dsccClientId: 'x', dsccClientSecret: 'y', dsccWorkspaceId: 'w'])
		DsccIndex idx = DsccIndex.build(new DsccClient(s, saved, { 0L }), 0L)

		List<Map> servers = (List<Map>) json.parse(new File(morph, 'hosts.json')).servers
		List<HostFacts> hosts = servers.findAll { it.computeServerType?.code == 'mvmHost' }.collect { Map h ->
			new HostFacts(id: h.id as Long, name: h.name, typeCode: 'mvmHost', vmHypervisor: true,
				luns: (h.volumes ?: []).collect { Map v ->
					String id = WwnMatcher.norm(v.deviceName?.startsWith('/dev/mapper/') ? v.deviceName : null) ?: WwnMatcher.norm(v.wwn)
					id ? [deviceId: id, device: v.deviceName, sizeBytes: v.maxStorage as Long] : null
				}.findAll().unique { it.deviceId })
		}
		List<DatastoreFacts> ds = new File(morph, 'ds').listFiles().collect { (Map) json.parse(it).datastore }.findAll { it?.refType == 'ComputeServerGroup' }.collect { Map d ->
			new DatastoreFacts(id: d.id as Long, name: d.name, typeCode: d.datastoreType?.code,
				lunId: WwnMatcher.norm(d.config?.blockDevice), sizeBytes: d.storageSize as Long, freeBytes: d.freeSpace as Long,
				hostIds: (d.locations ?: [])*.refId.collect { it as Long })
		}
		List<Map> vols = (List<Map>) json.parse(new File(morph, 'volumes.json')).storageVolumes
		GuestFacts pseudo = new GuestFacts(id: 1L, name: '(all VM disks)', disks: vols.findAll { it.refType == 'ComputeServerGroup' }.collect { Map v ->
			new DiskFacts(id: v.id as Long, name: v.name, device: v.deviceName, sizeBytes: v.maxStorage as Long,
				lunId: WwnMatcher.norm(v.wwn) ?: WwnMatcher.norm(v.externalId?.startsWith('/dev/mapper/') ? v.externalId : null),
				datastoreId: v.datastore?.id as Long, datastoreName: v.datastore?.name)
		})

		when:
		Map cluster = StorageMap.cluster('cluster', hosts, ds, [pseudo], idx)
		Map host = StorageMap.host(hosts[0], hosts, ds, [pseudo], idx)
		Map guest = StorageMap.guest([pseudo], ds.collectEntries { [(it.id): it] }, idx)
		println "index: ${idx.systems.size()} systems, ${idx.volumes.size()} volumes; notes ${idx.notes}"
		println "cluster totals: ${cluster.totals}; partial ${cluster.partialCount}; unmatched ${cluster.unmatchedCount}"
		println "arrays: " + cluster.arrays.collect { "${it.model} ${it.state} ${it.used}/${it.total} luns=${it.luns}" }
		println "datastores: " + cluster.datastores.collect { "${it.datastore}: matched=${it.matched} ${it.size} ${it.statusText} seen ${it.seenOn}" }
		println "integrated: " + cluster.integrated
		println "host ${hosts[0].name}: ${host.totals}"
		println "guest disks: ${guest.totals}"

		String out = System.getenv('PREVIEW_OUT')
		if (out) {
			new File(out).mkdirs()
			HandlebarsRenderer r = new HandlebarsRenderer('renderer', getClass().classLoader)
			Map extra = [asOf: DsccView.when(0L), arraysIndexed: idx.systems.size(), volumesIndexed: idx.volumes.size(), notes: []]
			[['dscc-cluster', cluster], ['dscc-host', host], ['dscc-guest', guest]].each { pair ->
				ViewModel<Map> vm = new ViewModel<>()
				vm.object = DsccView.withFlags((Map) pair[1] + extra)
				new File(out, "${pair[0]}.html").text = '<!doctype html><meta charset="utf-8"><body style="font-family:sans-serif;margin:16px">' +
					r.renderTemplate("hbs/${pair[0]}", vm).html
			}
		}

		then:
		idx.volumes.size() > 0
		cluster.state == 'cluster'
	}
}
