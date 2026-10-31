package io.github.grahamfairbank.dsccmorpheus

import spock.lang.Specification

class WwnMatcherSpec extends Specification {

	def "every Morpheus and DSCC form of one LUN id normalizes to the same id"() {
		expect:
		WwnMatcher.norm(raw) == want

		where:
		raw                                                  | want
		'60002AC0000000000000000A0001B000'                   | '60002ac0000000000000000a0001b000'   // DSCC device-type1/4 wwn
		'/dev/mapper/360002ac0000000000000000a0001b000'      | '60002ac0000000000000000a0001b000'   // host multipath device, GFS2 blockDevice
		'naa.60002ac0000000000000000a0001b000'               | '60002ac0000000000000000a0001b000'
		'60:00:2a:c0:00:00:00:00:00:00:00:0a:00:01:b0:00'    | '60002ac0000000000000000a0001b000'
		'/dev/mapper/2aaaa1111bbbb22226c9ce900aabbccdd'      | 'aaaa1111bbbb22226c9ce900aabbccdd'   // Nimble: "2" + serial_number
		'aaaa1111bbbb22226c9ce900aabbccdd'                   | 'aaaa1111bbbb22226c9ce900aabbccdd'   // DSCC device-type2 serial_number
		'0x600508b1001c00000000000000000001'                 | '600508b1001c00000000000000000001'   // local Smart Array disk
		'/dev/sdb'                                           | null
		'/dev/mapper/vg-root'                                | null
		''                                                   | null
		null                                                 | null
	}

	def "HPE array families are recognized, local and other vendors' disks are not"() {
		expect:
		WwnMatcher.family(id) == fam

		where:
		id                                   | fam
		'60002ac0000000000000000a0001b000'   | 'dt1/4'
		'aaaa1111bbbb22226c9ce900aabbccdd'   | 'dt2'
		'600508b1001c00000000000000000001'   | null
		'6001405abcdef0123456789abcdef012'   | null   // Linux LIO
		null                                 | null
	}

	def "short ids keep the part that differs between volumes"() {
		expect:
		WwnMatcher.shortId('60002ac0000000000000000a0001b000') == '…0000000a…'
		WwnMatcher.shortId('aaaa1111bbbb22226c9ce900aabbccdd') == 'aaaa1111…'
	}
}
