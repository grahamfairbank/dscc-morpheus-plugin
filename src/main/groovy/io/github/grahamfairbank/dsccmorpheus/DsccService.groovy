package io.github.grahamfairbank.dsccmorpheus

import groovy.util.logging.Slf4j

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

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
	/** A cluster facts entry read after this age is refreshed in the background (it is still served until FACTS_TTL_MS). */
	static final long FACTS_REFRESH_AFTER_MS = 45_000L
	/** One page view: Morpheus calls a tab's show() and then its render back to back; render reuses show()'s loads. */
	static final long PAGE_TTL_MS = 15_000L
	/** Memo entries kept; above this, expired entries go first, then the oldest. */
	static final int MEMO_MAX = 500
	/** How long a caller waits for another caller's read of the same key before reading itself. */
	static final long MEMO_WAIT_MS = 30_000L
	/** A failing background refresh is logged at most this often per key. */
	static final long REFRESH_WARN_EVERY_MS = 600_000L

	private final Closure<DsccTransport> transportFactory
	private final Closure<Long> clock
	private final ExecutorService executor
	private final ExecutorService refresher
	/**
	 * The client whose build is running, if any: one build per client at a time. A build of a replaced client
	 * (settings changed) doesn't block the new client's first build, even while it waits on DSCC; it never writes.
	 */
	private final AtomicReference<DsccClient> building = new AtomicReference<>()
	private final Map<String, Long> refreshWarned = new ConcurrentHashMap<>()

	private volatile DsccClient client
	private String connectionKey
	private volatile DsccIndex index
	private volatile String indexError
	private volatile long indexRequestedAt
	/** The index is fresh until then (clock ms): its build's finish plus INDEX_TTL_MS. */
	private volatile long indexFreshUntil
	/** After a failed build, no new build starts before then (clock ms). */
	private volatile long retryNotBefore

	private static final class Memo {
		final long at       // when its read started: the data is at least this fresh
		final long ttlMs
		final Object value
		Memo(long at, long ttlMs, Object value) { this.at = at; this.ttlMs = ttlMs; this.value = value }
	}
	private final Map<String, Memo> memo = new ConcurrentHashMap<>()
	/** Reads in progress, one per key: other callers of the key wait for it (single flight). */
	private final ConcurrentHashMap<String, CompletableFuture<Object>> loading = new ConcurrentHashMap<>()

	DsccService(Closure<DsccTransport> transportFactory = { DsccSettings s -> new JdkDsccTransport(s.proxyUrl) },
	            Closure<Long> clock = { System.currentTimeMillis() }, ExecutorService executor = null,
	            ExecutorService refresher = null) {
		this.transportFactory = transportFactory
		this.clock = clock
		this.executor = executor ?: newIndexExecutor()
		// Its own small thread, so a Morpheus refresh never waits behind (or delays) a DSCC index build.
		this.refresher = refresher ?: newRefresher()
	}

	/** Two threads: the current client's build, and room for one replaced client's build still waiting on DSCC. */
	private static ExecutorService newIndexExecutor() {
		ThreadPoolExecutor p = new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), { Runnable r ->
			Thread t = new Thread(r, 'dscc-morpheus-index')
			t.daemon = true
			t
		} as ThreadFactory)
		p.allowCoreThreadTimeOut(true)
		return p
	}

	private static ExecutorService newRefresher() {
		ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(16), { Runnable r ->
			Thread t = new Thread(r, 'dscc-morpheus-refresh')
			t.daemon = true
			t
		} as ThreadFactory)
		p.allowCoreThreadTimeOut(true)
		return p
	}

	void shutdown() {
		executor.shutdownNow()
		refresher.shutdownNow()
	}

	synchronized DsccClient clientFor(DsccSettings s) {
		String key = s.connectionKey()
		if (client == null || key != connectionKey) {
			client?.stop()   // a build still running for the old settings ends at its next DSCC call
			client = new DsccClient(s, transportFactory(s), clock)
			connectionKey = key
			index = null
			indexError = null
			indexRequestedAt = 0L
			indexFreshUntil = 0L
			retryNotBefore = 0L
		}
		return client
	}

	DsccIndex currentIndex() { index }

	/** Starts an index read in the background unless one is running, the index is fresh, or a retry must wait. */
	void requestIndex(DsccSettings s) {
		DsccClient c = clientFor(s)
		long now = clock.call()
		if (index != null && now < indexFreshUntil) return
		// After a failure, wait before retrying, whether or not older data is still shown.
		if (indexError && now < retryNotBefore) return
		DsccClient running = building.get()
		if (running != null && !running.stopped) return   // this client's build is running
		if (!building.compareAndSet(running, c)) return
		indexRequestedAt = now
		try {
			executor.submit { buildIndex(c) }
		} catch (Throwable e) {
			building.compareAndSet(c, null)
			throw e
		}
	}

	private void buildIndex(DsccClient c) {
		try {
			long start = clock.call()
			c.deadline = start + INDEX_BUILD_BUDGET_MS
			DsccIndex built = DsccIndex.build(c, start, c.is(client) ? index : null)
			synchronized (this) {   // with clientFor: a replaced client never writes
				if (c.is(client)) {
					index = built
					indexError = null
					indexFreshUntil = clock.call() + INDEX_TTL_MS   // counted from the finish: a slow build isn't redone at once
				}
			}
		} catch (DsccStoppedException e) {
			log.info('dscc-morpheus: index read for the previous settings stopped')
		} catch (DsccRateLimitException e) {
			long wait = Math.max(INDEX_RETRY_AFTER_ERROR_MS, e.retryAfterMs ?: 0L)
			failed(c, e.message, wait)
			log.warn("dscc-morpheus: index read stopped: ${e.message}; next read in ${wait.intdiv(1000L)} s")
		} catch (DsccBudgetException e) {
			failed(c, e.message, INDEX_TTL_MS)   // a read this slow isn't retried a minute later
			log.warn("dscc-morpheus: index read failed: ${e.message}")
		} catch (DsccException e) {
			failed(c, e.message, INDEX_RETRY_AFTER_ERROR_MS)
			log.warn("dscc-morpheus: index read failed: ${e.message}")
		} catch (Throwable e) {
			failed(c, unexpected('index read', e), INDEX_RETRY_AFTER_ERROR_MS)   // full detail in the log only
		} finally {
			c.deadline = 0L
			building.compareAndSet(c, null)
		}
	}

	/** Records a failed build, unless the settings changed meanwhile (a stale build must not paint the new one). */
	private synchronized void failed(DsccClient c, String message, long waitMs) {
		if (!c.is(client)) return
		indexError = message
		retryNotBefore = clock.call() + waitMs
	}

	/** Memoizes Morpheus reads for a short time (show() and render of one page, several tabs of one cluster). */
	Object remember(String key, long ttlMs, Closure<Object> read) { remember(key, ttlMs, 0L, read) }

	/**
	 * Memoizes a read for ttlMs. Callers of a key being read wait for that one read instead of each running it.
	 * With refreshAfterMs > 0, an entry read when older than that is still returned, and one background read
	 * replaces it, so frequent viewers don't wait. An entry past ttlMs is never returned: it is read again, as
	 * before, so data is never older than ttlMs. The read itself (with its tenant filter) runs in full every time.
	 * A null result isn't kept.
	 */
	Object remember(String key, long ttlMs, long refreshAfterMs, Closure<Object> read) {
		long now = clock.call()
		Memo hit = memo[key]
		if (hit != null && now - hit.at < ttlMs) {
			if (refreshAfterMs > 0L && now - hit.at >= refreshAfterMs) refreshInBackground(key, ttlMs, read)
			return hit.value
		}
		CompletableFuture<Object> mine = new CompletableFuture<>()
		CompletableFuture<Object> other = loading.putIfAbsent(key, mine)
		if (other != null) {
			try {
				return other.get(MEMO_WAIT_MS, TimeUnit.MILLISECONDS)
			} catch (TimeoutException e) {
				log.info("dscc-morpheus: still waiting for ${key} after ${MEMO_WAIT_MS.intdiv(1000L)} s, reading it again")
				return read.call()
			} catch (ExecutionException e) {
				if (e.cause instanceof RejectedExecutionException) return remember(key, ttlMs, refreshAfterMs, read)
				throw e.cause ?: e
			}
		}
		Memo again = memo[key]   // another caller may have finished between the two looks
		if (again != null && clock.call() - again.at < ttlMs) {
			loading.remove(key, mine)
			mine.complete(again.value)
			return again.value
		}
		return load(key, ttlMs, read, mine)
	}

	private Object load(String key, long ttlMs, Closure<Object> read, CompletableFuture<Object> future) {
		try {
			long start = clock.call()
			Object v = read.call()
			if (v != null) store(key, new Memo(start, ttlMs, v))
			future.complete(v)
			return v
		} catch (Throwable e) {
			future.completeExceptionally(e)
			throw e
		} finally {
			loading.remove(key, future)
		}
	}

	private void refreshInBackground(String key, long ttlMs, Closure<Object> read) {
		CompletableFuture<Object> future = new CompletableFuture<>()
		if (loading.putIfAbsent(key, future) != null) return   // already being read
		try {
			refresher.execute {
				try {
					load(key, ttlMs, read, future)
				} catch (Throwable e) {
					long t = System.currentTimeMillis()
					Long last = refreshWarned[key]
					if (last == null || t - last > REFRESH_WARN_EVERY_MS) {
						refreshWarned[key] = t
						if (refreshWarned.size() > 2000) refreshWarned.clear()
						log.warn("dscc-morpheus: background refresh of ${key} failed (logged once per 10 minutes): ${e}")
					}
				}
			}
		} catch (RejectedExecutionException e) {
			loading.remove(key, future)
			future.completeExceptionally(e)   // a caller waiting on it reads itself
		}
	}

	private void store(String key, Memo m) {
		memo[key] = m
		if (memo.size() > MEMO_MAX) evict()
	}

	/** Drops expired entries, then the oldest, instead of clearing everything (cluster entries are costly to read). */
	private synchronized void evict() {
		if (memo.size() <= MEMO_MAX) return
		long now = clock.call()
		memo.entrySet().removeIf { Map.Entry<String, Memo> e -> now - e.value.at >= e.value.ttlMs }
		if (memo.size() > MEMO_MAX) {
			int drop = memo.size() - (int) (MEMO_MAX * 0.9)
			memo.entrySet().sort(false) { Map.Entry<String, Memo> e -> e.value.at }.take(drop).each { Map.Entry<String, Memo> e -> memo.remove(e.key, e.value) }
		}
	}

	int memoSize() { memo.size() }

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
