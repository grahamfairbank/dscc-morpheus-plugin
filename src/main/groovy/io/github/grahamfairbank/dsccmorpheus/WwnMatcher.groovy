package io.github.grahamfairbank.dsccmorpheus

import groovy.transform.CompileStatic

/**
 * One device id for a SAN LUN, whatever form Morpheus or DSCC stores it in.
 *
 * Measured on Morpheus 9.0.1 and DSCC (2026-10-29):
 * - Hosts list multipath LUNs as /dev/mapper/&lt;scsi_id&gt;: "3" + NAA WWN for 3PAR/Primera/Alletra 9000/MP,
 *   "2" + the DSCC serial_number for Nimble/Alletra 6000/5000.
 * - Integrated Alletra MP VM disks carry the WWN (no prefix) and externalId /dev/mapper/3&lt;wwn&gt;.
 * - GFS2 datastores keep config.blockDevice = /dev/mapper/&lt;scsi_id&gt;.
 * - DSCC device-type1/4 volumes have an uppercase 32-hex wwn; device-type2 volumes a lowercase serial_number.
 */
@CompileStatic
class WwnMatcher {

	static final String OUI_3PAR = '0002ac'
	static final String OUI_NIMBLE = '6c9ce9'

	/** Lowercase 32- or 16-hex id with prefixes and separators removed, or null if it isn't a device id. */
	static String norm(Object raw) {
		String w = raw?.toString()?.trim()?.toLowerCase()
		if (!w) return null
		int slash = w.lastIndexOf('/')
		if (slash >= 0) w = w.substring(slash + 1)
		for (String x : ['naa.', 'eui.', 'wwn-0x', '0x']) {
			if (w.startsWith(x)) w = w.substring(x.length())
		}
		w = w.replace(':', '').replace('-', '')
		if (w.length() == 33 && (w.charAt(0) == '2' as char || w.charAt(0) == '3' as char)) w = w.substring(1)
		if (!(w.length() == 32 || w.length() == 16)) return null
		return w ==~ /[0-9a-f]+/ ? w : null
	}

	/** True for ids of HPE array LUNs (3PAR family NAA, or Nimble serial). */
	static boolean isHpeArray(String id) { family(id) != null }

	/** 'dt1/4' for 3PAR-family ids, 'dt2' for Nimble-family ids, else null. */
	static String family(String id) {
		if (!id) return null
		if (id.length() == 32 && id.substring(1, 7) == OUI_3PAR) return 'dt1/4'
		if (id.contains(OUI_NIMBLE)) return 'dt2'
		return null
	}

	/**
	 * Short form for tables (the full id is in the copy button and tooltip), keeping the 8 digits that differ
	 * between volumes: a 3PAR-family WWN is NAA+OUI (16), volume id (8), array id (8); a Nimble serial starts
	 * with its volume part and ends with OUI + group.
	 */
	static String shortId(String id) {
		if (!id) return '--'
		if (id.length() != 32) return id
		switch (family(id)) {
			case 'dt1/4': return "…${id.substring(16, 24)}…".toString()
			case 'dt2': return "${id.substring(0, 8)}…".toString()
			default: return "${id.substring(0, 8)}…".toString()
		}
	}
}
