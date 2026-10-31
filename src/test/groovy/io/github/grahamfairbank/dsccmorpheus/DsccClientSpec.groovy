package io.github.grahamfairbank.dsccmorpheus

import spock.lang.Specification

class DsccClientSpec extends Specification {

	FakeDscc fake = new FakeDscc()
	long now = 1_000_000L

	def "signs in through the workspace issuer first and sends limit and offset on every list call"() {
		given:
		DsccClient c = new DsccClient(Fixtures.settings(), fake, { now })

		when:
		List<Map> vols = c.volumes(4, Fixtures.MP_ID, 0L)

		then:
		vols.size() == 5
		fake.calls[0].startsWith('POST https://global.api.greenlake.hpe.com/authorization/v2/oauth2/ws000000000000000000000000000001/token')
		fake.calls.findAll { it.startsWith('GET') }.every { it.contains('limit=') && it.contains('offset=') }
	}

	def "pages until total is reached"() {
		given:
		fake.pageLimit = 3
		DsccClient c = new DsccClient(Fixtures.settings(), fake, { now })

		expect:
		c.volumes(4, Fixtures.MP_ID, 0L)*.id == ['v1', 'v2', 'v3', 'v4', 'v5']
		fake.calls.count { it.contains('/volumes?') } == 2
	}

	def "without a workspace id the legacy token gets 'failed to authorize', reported as a setup problem"() {
		given:
		DsccClient c = new DsccClient(Fixtures.settings([dsccWorkspaceId: '']), fake, { now })

		when:
		c.systems(4, 0L)

		then:
		DsccException e = thrown()
		e.message.contains('authorized no access')
		e.message.contains('workspace ID')
	}

	def "tokens are reused until a minute before expiry"() {
		given:
		DsccClient c = new DsccClient(Fixtures.settings(), fake, { now })

		when:
		c.systems(4, 0L)
		now += 800_000L       // 13 min: still valid (expires_in 899 s)
		c.systems(4, 0L)
		now += 60_000L        // within the last minute: renewed
		c.systems(4, 0L)

		then:
		fake.calls.count { it.startsWith('POST') } == 2
	}

	def "a 401 drops the token and retries once"() {
		given:
		int n = 0
		DsccTransport t = { String m, String u, Map h, String b ->
			if (m == 'POST') return new HttpResult(200, '{"access_token":"t' + (++n) + '","expires_in":899}')
			return h.Authorization == 'Bearer t1' ? new HttpResult(401, '') : new HttpResult(200, '{"items":[],"total":0}')
		} as DsccTransport
		DsccClient c = new DsccClient(Fixtures.settings(), t, { now })

		expect:
		c.systems(1, 0L) == []
		n == 2
	}

	def "error bodies are logged, not shown"() {
		given:
		fake.override['/api/v1/storage-systems/device-type4'] = new HttpResult(500, '{"error":"internal detail 10.0.0.1"}')
		DsccClient c = new DsccClient(Fixtures.settings(), fake, { now })

		when:
		c.systems(4, 0L)

		then:
		DsccException e = thrown()
		e.message.contains('HTTP 500')
		!e.message.contains('10.0.0.1')
	}
}
