package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import spock.lang.IgnoreIf
import spock.lang.Specification

/**
 * Opt-in: PREVIEW_OUT=<folder> renders the three tabs from the synthetic Fixtures (no real identifiers) to HTML,
 * for the README screenshots in docs/images (shot with tools/screenshots.py).
 */
@IgnoreIf({ !System.getenv('PREVIEW_OUT') || System.getenv('DSCC_FIXTURES') })
class PreviewSpec extends Specification {

	def "render the tabs from test data"() {
		given:
		FakeDscc fake = new FakeDscc()
		long now = 1793300000000L
		DsccService service = new DsccService({ DsccSettings s -> fake }, { now }, new DirectExecutor())
		DsccSettings s = Fixtures.settings()
		List<HostFacts> hosts = Fixtures.hosts()
		Closure<Map> cluster = { [hosts: hosts, datastores: Fixtures.datastores(), guests: Fixtures.guests()] }
		Closure<Map> host = { [host: hosts[0], hosts: hosts, datastores: Fixtures.datastores(), guests: Fixtures.guests()] }
		Closure<Map> guest = { [guests: [Fixtures.guests()[0]], datastores: Fixtures.datastores().collectEntries { [(it.id): it] }] }
		service.clusterTab('cluster-a', cluster, s)
		HandlebarsRenderer r = new HandlebarsRenderer('renderer', getClass().classLoader)
		File out = new File(System.getenv('PREVIEW_OUT'))
		out.mkdirs()

		when:
		[['dscc-cluster', service.clusterTab('cluster-a', cluster, s)],
		 ['dscc-host', service.hostTab('host-a', host, s)],
		 ['dscc-guest', service.guestTab('vm-a', guest, s)]].each { pair ->
			ViewModel<Map> vm = new ViewModel<>()
			vm.object = DsccView.withFlags((Map) pair[1])
			new File(out, "${pair[0]}.html").text = '<!doctype html><meta charset="utf-8">' +
				'<body style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;margin:16px;background:#fff;color:#1b1b1b">' +
				r.renderTemplate("hbs/${pair[0]}", vm).html
		}

		then:
		new File(out, 'dscc-cluster.html').text.contains('mp-array-a')
	}
}
