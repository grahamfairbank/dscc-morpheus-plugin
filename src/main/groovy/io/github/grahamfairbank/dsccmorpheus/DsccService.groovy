package io.github.grahamfairbank.dsccmorpheus

import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the DSCC client and the index of every array volume, and builds the three tab models from Morpheus facts.
 * The index is read in the background (COM plugin pattern): a tab never waits on DSCC; until the first read
 * finishes it says so. Free of Morpheus classes (the providers convert Morpheus objects to facts).
 */
@Slf4j
class DsccService {

	/** Array capacity and health change slowly; volumes appear when VMs are created. */
	static final long INDEX_TTL_MS = 5L * 60_000L
	static final long INDEX_RETRY_AFTER_ERROR_MS = 60_000L
	/** A whole index read stops after this; the previous index stays in use. */
	static final long INDEX_BUILD_BUDGET_MS = 10L * 60_000L
	/** Morpheus facts per cluster (hosts, datastores, VMs), shared by the tabs of one cluster for a short time. */
	static final long FACTS_TTL_MS = 60_000L

	private final Closure<DsccTransport> transportFactory
	private final Closure<Long> clock
	private final ExecutorService executor
	private final AtomicBoolean building = new AtomicBoolean(false)

	private DsccClient client
	private String connectionKey
	private volatile DsccIndex index
	private volatile String indexError
	private volatile long indexRequestedAt
	private final Map<String, List> memo = new ConcurrentHashMap<>()

	DsccService(Closure<DsccTransport> transportFactory = { DsccSettings s -> new JdkDsccTransport(s.proxyUrl) },
	            Closure<Long> clock = { System.currentTimeMillis() }, ExecutorService executor = null) {
		this.transportFactory = transportFactory
		this.clock = clock
		this.executor = executor ?: Executors.newSingleThreadExecutor({ Runnable r ->
			Thread t = new Thread(r, 'dscc-morpheus-index')
			t.daemon = true
			t
		} as ThreadFactory)
	}

	void shutdown() { executor.shutdownNow() }

	synchronized DsccClient clientFor(DsccSettings s) {
		String key = s.connectionKey()
		if (client == null || key != connectionKey) {
			client = new DsccClient(s, transportFactory(s), clock)
			connectionKey = key
			index = null
			indexError = null
			indexRequestedAt = 0L
		}
		return client
	}

	DsccIndex currentIndex() { index }

	/** Starts an index read in the background unless one is running or the index is fresh. */
	void requestIndex(DsccSettings s) {
		DsccClient c = clientFor(s)
		long now = clock.call()
		if (index != null && now - index.builtAt < INDEX_TTL_MS) return
		// After a failure, wait before retrying, whether or not older data is still shown.
		if (indexError && now - indexRequestedAt < INDEX_RETRY_AFTER_ERROR_MS) return
		if (!building.compareAndSet(false, true)) return
		indexRequestedAt = now
		executor.submit {
			try {
				long start = clock.call()
				c.deadline = start + INDEX_BUILD_BUDGET_MS
				DsccIndex built = DsccIndex.build(c, start)
				if (c.is(client)) { index = built; indexError = null }
			} catch (DsccException e) {
				indexError = e.message
				log.warn("dscc-morpheus: index read failed: ${indexError}")
			} catch (Throwable e) {
				indexError = unexpected('index read', e)   // full detail in the log only
			} finally {
				c.deadline = 0L
				building.set(false)
			}
		}
	}

	/** Memoizes Morpheus reads for a short time (several tabs of one cluster render together). */
	Object remember(String key, long ttlMs, Closure<Object> read) {
		long now = clock.call()
		List hit = memo[key]
		if (hit && now - (hit[0] as long) < ttlMs) return hit[1]
		Object v = read.call()
		memo[key] = [now, v]
		if (memo.size() > 500) memo.clear()
		return v
	}

	// ---- tab models (never throw: problems become a "state" the template explains) ------------------

	Map clusterTab(String clusterName, Closure<Map> facts, DsccSettings s) {
		withIndex(s, [clusterName: clusterName]) { DsccIndex idx ->
			Map f = facts.call()
			StorageMap.cluster(clusterName, (List<HostFacts>) f.hosts, (Collection<DatastoreFacts>) f.datastores,
				(Collection<GuestFacts>) f.guests, idx)
		}
	}

	Map hostTab(String hostName, Closure<Map> facts, DsccSettings s) {
		withIndex(s, [hostName: hostName]) { DsccIndex idx ->
			Map f = facts.call()
			StorageMap.host((HostFacts) f.host, (List<HostFacts>) f.hosts, (Collection<DatastoreFacts>) f.datastores,
				(Collection<GuestFacts>) f.guests, idx)
		}
	}

	Map guestTab(String name, Closure<Map> facts, DsccSettings s) {
		withIndex(s, [vmName: name]) { DsccIndex idx ->
			Map f = facts.call()
			StorageMap.guest((List<GuestFacts>) f.guests, (Map<Long, DatastoreFacts>) f.datastores, idx)
		}
	}

	private Map withIndex(DsccSettings s, Map base, Closure<Map> build) {
		base = base + [dsccUrl: s.uiUrl]
		if (!s.configured) return base + [state: 'unconfigured']
		try {
			clientFor(s)
			DsccIndex idx = index
			requestIndex(s)   // first read, or a background refresh when older than INDEX_TTL_MS
			if (idx == null) return base + [state: indexError ? 'error' : 'building', error: indexError]
			Map m = build.call(idx)
			List<String> notes = ((List<String>) (m.notes ?: [])) + idx.notes
			String failed = indexError
			if (failed) notes = ["The last DSCC refresh failed, so this shows data from ${DsccView.when(idx.builtAt)}: ${failed}".toString()] + notes
			return base + m + [asOf: DsccView.when(idx.builtAt), notes: notes, arraysIndexed: idx.systems.size(),
			                   volumesIndexed: idx.volumes.size()]
		} catch (DsccException e) {
			return base + [state: 'error', error: e.message]
		} catch (Throwable e) {
			return base + [state: 'error', error: unexpected('tab', e)]
		}
	}

	/** Logs an unexpected error in full and returns a short message with a reference to find it in the log. */
	static String unexpected(String what, Throwable e) {
		String ref = String.format('%08x', ThreadLocalRandom.current().nextInt())
		log.warn("dscc-morpheus: ${what} failed (ref ${ref})", e)
		return "Unexpected error (ref ${ref}). Details are in the Morpheus log.".toString()
	}
}
