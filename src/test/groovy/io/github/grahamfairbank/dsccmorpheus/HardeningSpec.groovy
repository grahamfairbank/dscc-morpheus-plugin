package io.github.grahamfairbank.dsccmorpheus

import spock.lang.Specification

/** Limits and failure handling added after the 2026-10-31 security review (T3-1 to T3-6). */
class HardeningSpec extends Specification {

	FakeDscc fake = new FakeDscc()
	long now = 1_000L
	DsccService service = new DsccService({ DsccSettings s -> fake }, { now }, new DirectExecutor())
	Closure<Map> clusterFacts = { [hosts: Fixtures.hosts(), datastores: Fixtures.datastores(), guests: Fixtures.guests()] }

	private Map ready(DsccSettings s) {
		service.clusterTab('cl', clusterFacts, s)
		return service.clusterTab('cl', clusterFacts, s)
	}

	def "a response body over the limit is not read"() {
		when:
		JdkDsccTransport.readLimited(new ByteArrayInputStream(new byte[1001]), 1000L)

		then:
		thrown(ResponseTooLargeException)

		expect:
		JdkDsccTransport.readLimited(new ByteArrayInputStream(new byte[1000]), 1000L).length == 1000
	}

	def "a too-large DSCC response becomes a short message for the tab"() {
		given:
		fake.onGet = { String url -> throw new ResponseTooLargeException(32L * 1024L * 1024L) }

		when:
		Map m = ready(Fixtures.settings())

		then:
		m.state == 'error'
		m.error.contains('larger than 32 MiB')
	}

	def "an index read that runs past its time budget stops, and later calls are not made"() {
		given: 'every GET takes four minutes on the test clock'
		fake.onGet = { String url -> now += 4L * 60_000L }

		when: 'one render starts the read (the direct executor runs it to the end)'
		service.clusterTab('cl', clusterFacts, Fixtures.settings())

		then: 'it stopped at the fourth GET, past 10 minutes'
		service.@indexError.contains('longer than 10 minutes')
		fake.calls.count { it.startsWith('GET') } == 3
		service.currentIndex() == null
	}

	def "an unexpected error shows a log reference, not the exception text"() {
		given: 'DSCC answers with a shape the plugin does not expect'
		fake.override['/api/v1/storage-systems/device-type1'] = new HttpResult(200, '{"items":"internal detail 12345","total":1}')

		when:
		Map m = ready(Fixtures.settings())

		then:
		m.state == 'error'
		m.error.startsWith('Unexpected error (ref ')
		!m.error.contains('internal detail')
	}

	def "after a failed refresh the old data is shown with a note, and retries wait a minute"() {
		given:
		DsccSettings s = Fixtures.settings()
		assert ready(s).state == 'cluster'

		when: 'the index is stale and DSCC now fails'
		now += DsccService.INDEX_TTL_MS + 1
		fake.override['/api/v1/storage-systems/device-type1'] = new HttpResult(500, 'boom')
		service.clusterTab('cl', clusterFacts, s)
		int gets = fake.calls.count { it.startsWith('GET') }
		Map m = service.clusterTab('cl', clusterFacts, s)
		service.clusterTab('cl', clusterFacts, s)

		then: 'the old data with a note, and no new read within the minute'
		m.state == 'cluster'
		m.notes.any { it.startsWith('The last DSCC refresh failed') && it.contains('HTTP 500') }
		fake.calls.count { it.startsWith('GET') } == gets

		when: 'a minute later DSCC is back'
		fake.override.clear()
		now += DsccService.INDEX_RETRY_AFTER_ERROR_MS + 1
		service.clusterTab('cl', clusterFacts, s)
		Map after = service.clusterTab('cl', clusterFacts, s)

		then:
		!after.notes.any { it.startsWith('The last DSCC refresh failed') }
	}

	def "the connection key does not contain the client secret"() {
		given:
		DsccSettings s = Fixtures.settings()

		expect:
		!s.connectionKey().contains(s.clientSecret)
		s.connectionKey() == Fixtures.settings().connectionKey()
		s.connectionKey() != Fixtures.settings([dsccClientSecret: 'other']).connectionKey()
	}
}
