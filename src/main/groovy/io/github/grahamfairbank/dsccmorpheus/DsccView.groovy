package io.github.grahamfairbank.dsccmorpheus

import java.util.regex.Pattern

/** Small formatting helpers shared by the storage map and the tabs. */
class DsccView {

	static final List<String> UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB']
	private static final Pattern POINT_ZERO = Pattern.compile('\\.0$')

	/** 1649267441664 -> "1.5 TiB"; null -> "--". Binary units, as the arrays report them. */
	static String bytes(Long b) {
		if (b == null) return '--'
		double v = b as double
		int u = 0
		while (v >= 1024d && u < UNITS.size() - 1) { v /= 1024d; u++ }
		String n = v >= 100 || u == 0 ? String.format('%.0f', v) : String.format('%.1f', v)
		return "${POINT_ZERO.matcher(n).replaceFirst('')} ${UNITS[u]}".toString()
	}

	/** 3.3 -> "3.3:1", 25.69 -> "25.7:1"; null -> "--". */
	static String ratio(Double r) {
		if (r == null) return '--'
		return "${POINT_ZERO.matcher(String.format('%.1f', r)).replaceFirst('')}:1".toString()
	}

	/** Percent used, whole number, or null when unknown. */
	static Integer pct(Long used, Long total) {
		if (used == null || total == null || total <= 0) return null
		return Math.min(100, Math.round(used * 100d / total) as int)
	}

	/** 2026-10-29T13:58:46Z -> 2026-10-29 13:58 UTC. */
	static String when(long epochMs) {
		String s = java.time.Instant.ofEpochMilli(epochMs).toString()
		return "${s.substring(0, 10)} ${s.substring(11, 16)} UTC".toString()
	}

	/** Adds isXxx flags for the model's state, for the logic-less templates. */
	static Map withFlags(Map model) {
		Map m = new LinkedHashMap(model ?: [state: 'error', error: 'No data'])
		String st = (m.state ?: 'error') as String
		m["is${st.substring(0, 1).toUpperCase()}${st.substring(1)}".toString()] = true
		return m
	}
}
