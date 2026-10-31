package io.github.grahamfairbank.dsccmorpheus

import spock.lang.Specification

class DsccSettingsSpec extends Specification {

	def "defaults to us1 and is unconfigured without a client"() {
		when:
		DsccSettings s = DsccSettings.fromJson(null)

		then:
		!s.configured
		s.apiUrl == 'https://us1.data.cloud.hpe.com'
		s.uiUrl == 'https://console-us1.data.cloud.hpe.com'
		s.tokenUrls() == [DsccSettings.LEGACY_TOKEN_URL]
	}

	def "region picks the API and console hosts"() {
		expect:
		DsccSettings.fromMap([dsccRegion: region]).with { apiUrl == api && uiUrl == ui }

		where:
		region                            | api                               | ui
		'eu1'                             | 'https://eu1.data.cloud.hpe.com'  | 'https://console-eu1.data.cloud.hpe.com'
		'JP1'                             | 'https://jp1.data.cloud.hpe.com'  | 'https://console-jp1.data.cloud.hpe.com'
		'https://eu1.data.cloud.hpe.com/' | 'https://eu1.data.cloud.hpe.com'  | 'https://console-eu1.data.cloud.hpe.com'
		'nowhere'                         | 'https://us1.data.cloud.hpe.com'  | 'https://console-us1.data.cloud.hpe.com'
	}

	def "workspace issuers come before legacy SSO"() {
		expect:
		Fixtures.settings().tokenUrls() == [
			'https://global.api.greenlake.hpe.com/authorization/v2/oauth2/ws000000000000000000000000000001/token',
			'https://aquila-org-api.common.cloud.hpe.com/authorization/v2/oauth2/ws000000000000000000000000000001/token',
			DsccSettings.LEGACY_TOKEN_URL]
		Fixtures.settings([dsccTokenUrl: 'https://example.test/token']).tokenUrls() == ['https://example.test/token']
	}

	def "console links only for relative consoleUri values"() {
		given:
		DsccSettings s = Fixtures.settings()

		expect:
		s.consoleLink('/block/volumes/device-type4/x?systemUid=y') == 'https://console-us1.data.cloud.hpe.com/block/volumes/device-type4/x?systemUid=y'
		s.consoleLink('null') == null
		s.consoleLink('https://evil.test/') == null
		s.consoleLink(null) == null
	}

	def "bad JSON gives defaults"() {
		expect:
		!DsccSettings.fromJson('{not json').configured
	}
}
