package io.github.grahamfairbank.dsccmorpheus

import spock.lang.Specification

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The 2026-10-31 performance changes: DSCC select, 429 and failed-array handling, index timing, the memo. */
class PerformanceSpec extends Specification {

	FakeDscc fake = new FakeDscc()
	long now = 1_000L
	DsccService service = new DsccService({ DsccSettings s -> fake }, { now }, new DirectExecutor(), new DirectExecutor())
	Closure<Map> clusterFacts = { [hosts: Fixtures.hosts(), datastores: Fixtures.datastores(), guests: Fixtures.guests()] }

	private Map ready(DsccSettings s = Fixtures.settings()) {
		service.clusterTab('cl', clusterFacts, s)
		return service.clusterTab('cl', clusterFacts, s)
	}

	private DsccClient client() { new DsccClient(Fixtures.settings(), fake, { now }) }

	private static Map fields(DsccIndex idx) {
		idx.volumes.collectEntries { k, ArrayVolume v -> [(k): [v.id, v.name, v.systemKey, v.sizeBytes, v.usedBytes, v.pool, v.state, v.level, v.url]] }
	}

	private int gets(String part) { fake.calls.count { it.startsWith('GET') && it.contains(part) } }

	// ---- D4: select ------------------------------------------------------------------------------

	def "volume lists ask only for the fields the index reads, and the index is the same as without select"() {
		given: 'all three families'
		fake.withDt1 = true
		DsccClient withSelect = client()
		DsccIndex selected = DsccIndex.build(withSelect, now)
		List<String> volumeCalls = fake.calls.findAll { it.contains('/volumes?') }
		FakeDscc plainFake = new FakeDscc(selectRefused: true, withDt1: true)
		DsccIndex plain = DsccIndex.build(new DsccClient(Fixtures.settings(), plainFake, { now }), now)

		expect: 'select sent for each family, with the exact keys'
		volumeCalls.size() == 3
		volumeCalls.every { it.contains('select=') }
		volumeCalls.find { it.contains('device-type1') }.contains("select=${DsccClient.VOLUME_SELECT[1]}&")
		volumeCalls.find { it.contains('device-type4') }.contains("select=${DsccClient.VOLUME_SELECT[4]}&")
		volumeCalls.find { it.contains('device-type2') }.contains("select=${DsccClient.VOLUME_SELECT[2]}&")
		withSelect.usesSelect(1) && withSelect.usesSelect(2) && withSelect.usesSelect(4)

		and: 'a parser reading a field missing from the select list would make these differ'
		fields(selected) == fields(plain)
		selected.volume(WwnMatcher.norm(Fixtures.WWN_DS)).with { sizeBytes == 2L * 1024 * 1024 * 1024 * 1024 && pool == 'SSD_r6' && url.contains('/block/volumes/device-type4/v1') }
		selected.volume(Fixtures.NIMBLE_SERIAL).pool == 'default'
		selected.volume('60002ac0000000000000002100010001').with { sizeBytes == 100L * 1024 * 1024 * 1024 && usedBytes == 2L * 1024 * 1024 * 1024 && pool == 'SSD_r6' && level == 'ok' }
		selected.volume('60002ac0000000000000002200010001').with { pool == 'FC_r6_snap' && level == 'warn' }
		selected.volumes.size() == 8
	}

	def "a selected page with no #missing is read again without select, and that family stops using it"() {
		given:
		fake.withDt1 = true
		fake.selectDrops = [(fam): drops as Set]
		DsccClient c = client()

		when:
		DsccIndex idx = DsccIndex.build(c, now)

		then: 'the index is complete, and only that family stopped using select'
		idx.volumes.size() == 8
		idx.notes.isEmpty()
		!c.usesSelect(fam)
		([1, 2, 4] - fam).every { c.usesSelect(it) }

		where:
		fam | drops                                              | missing
		1   | ['wwn']                                            | 'dt1 device ids'
		1   | ['state']                                          | 'dt1 states'
		4   | ['wwn']                                            | 'dt4 device ids'
		4   | ['state']                                          | 'dt4 states'
		4   | ['volumeCapacity.sizeMiB']                         | 'dt4 sizes'
		2   | ['serial_number']                                  | 'dt2 serial numbers'
		2   | ['vol_state', 'online']                            | 'dt2 states'
	}

	def "the sanity check doesn't blame select for data that is like that anyway"() {
		expect:
		DsccClient.selectedPageProblem(4, []) == null
		DsccClient.selectedPageProblem(2, [[serial_number: 'x', online: false]]) == null
		DsccClient.selectedPageProblem(1, [[wwn: 'x', state: [overall: 'STATE_NORMAL']]]) == null
		DsccClient.selectedPageProblem(4, [[wwn: 'x', state: [overall: 'STATE_NORMAL']]]) == 'its answers had no volume sizes'
	}

	def "a select-only HTTP 400 other than ERR_INVALID_KEY falls back for an hour, then select is tried again"() {
		given:
		fake.selectRefused = true
		fake.selectErrorCode = 'ERR_SOMETHING_ELSE'
		DsccClient c = client()

		when:
		DsccIndex idx = DsccIndex.build(c, now)

		then:
		idx.volumes.size() == 6
		!c.usesSelect(4) && !c.usesSelect(2)

		when: 'within the hour: no select is sent'
		fake.selectRefused = false
		now += DsccClient.SELECT_PAUSE_MS - 1
		int before = fake.calls.count { it.contains('select=') }
		DsccIndex.build(c, now)

		then:
		fake.calls.count { it.contains('select=') } == before

		when: 'after the hour'
		now += 2
		DsccIndex.build(c, now)

		then:
		c.usesSelect(4) && c.usesSelect(2)
		fake.calls.count { it.contains('select=') } == before + 2
	}

	def "every select key is a field of that family's volume schema (mirror of storage-api.json in FakeDscc)"() {
		expect:
		DsccClient.VOLUME_SELECT.every { int fam, String keys -> keys.split(',').every { it in fake.volumeKeys[fam] } }
		!DsccClient.VOLUME_SELECT[4].split(',').contains('sizeMiB')       // device-type4 has no top-level size
		!DsccClient.VOLUME_SELECT[1].contains('volumeCapacity')           // device-type1 has no volumeCapacity
	}

	def "an unknown select key (HTTP 400) is retried without select, and that family stops using it"() {
		given: 'DSCC does not know one of the device-type4 keys'
		fake.volumeKeys[4] = fake.volumeKeys[4] - 'usrCpgShortName'
		DsccClient c = client()

		when:
		DsccIndex first = DsccIndex.build(c, now)
		DsccIndex second = DsccIndex.build(c, now)

		then: 'the volumes are all there, read without select'
		first.volumes.size() == 6 && second.volumes.size() == 6
		first.notes.isEmpty()
		!c.usesSelect(4) && c.usesSelect(2)
		fake.calls.count { it.contains('device-type4') && it.contains('/volumes?select=') } == 1   // only the first build tried
		fake.calls.count { it.contains('device-type2') && it.contains('/volumes?select=') } == 2
	}

	def "an HTTP 400 that also happens without select is reported, and select stays on"() {
		given:
		fake.override["/api/v1/storage-systems/device-type4/${Fixtures.MP_ID}/volumes".toString()] = new HttpResult(400, '{"errorCode":"ERR_SOMETHING"}')
		DsccClient c = client()

		when:
		DsccIndex idx = DsccIndex.build(c, now)

		then:
		idx.systems["dt4:${Fixtures.MP_ID}".toString()].note.contains('HTTP 400')
		c.usesSelect(4)
	}

	def "device-type4: select answers without any size fall back to a read without select"() {
		given: 'volumes that keep their sizes only at the top level'
		fake.dt4VolumeItems = Fixtures.dt4Volumes().collect { Map v ->
			Map cap = (Map) v.remove('volumeCapacity')
			v + [sizeMiB: cap.sizeMiB, usedSizeMiB: cap.usedSizeMiB]
		}
		DsccClient c = client()

		when:
		DsccIndex idx = DsccIndex.build(c, now)

		then:
		idx.volume(WwnMatcher.norm(Fixtures.WWN_DS)).sizeBytes == 2L * 1024 * 1024 * 1024 * 1024
		!c.usesSelect(4) && c.usesSelect(2)
	}

	// ---- D3: 429 and a failed array --------------------------------------------------------------

	def "a failed volume read keeps that array's previous volumes, with a note"() {
		given:
		DsccClient c = client()
		DsccIndex before = DsccIndex.build(c, now)
		fake.override["/api/v1/storage-systems/device-type4/${Fixtures.MP_ID}/volumes".toString()] = new HttpResult(503, 'busy')

		when:
		DsccIndex after = DsccIndex.build(c, now + 300_000L, before)
		DsccIndex third = DsccIndex.build(c, now + 600_000L, after)

		then: 'the LUNs still match; the note says how old they are (the first read, also after two failures)'
		after.volume(WwnMatcher.norm(Fixtures.WWN_DS)).name == 'mp-ds01'
		after.volumes.size() == before.volumes.size()
		after.notes.any { it.contains("so its volumes are from ${DsccView.when(now)}") }
		third.notes.any { it.contains("so its volumes are from ${DsccView.when(now)}") }
		third.volume(WwnMatcher.norm(Fixtures.WWN_DS)) != null

		and: 'without a previous index there is nothing to keep'
		DsccIndex.build(c, now).with { volume(WwnMatcher.norm(Fixtures.WWN_DS)) == null && notes.any { it.contains("Couldn't read this system's volumes: ") } }
	}

	def "a 429 stops the build, keeps the previous index, and the next read waits for Retry-After"() {
		given:
		DsccSettings s = Fixtures.settings()
		assert ready(s).state == 'cluster'
		now += DsccService.INDEX_TTL_MS + 1
		fake.override["/api/v1/storage-systems/device-type2/${Fixtures.NIMBLE_ID}/volumes".toString()] = new HttpResult(429, '', ['Retry-After': ['300']])
		int before = fake.calls.size()

		when:
		service.clusterTab('cl', clusterFacts, s)
		Map m = service.clusterTab('cl', clusterFacts, s)

		then: 'the device-type4 array (read after it) was not asked; the old matches stay'
		fake.calls.drop(before).every { !it.contains('device-type4') }
		m.state == 'cluster'
		m.totals.matched == 5
		m.notes.any { it.startsWith('The last DSCC refresh failed') && it.contains('429') }

		when: 'a minute later: still waiting (Retry-After 300 s)'
		fake.override.clear()
		int calls = fake.calls.size()
		now += 61_000L
		service.clusterTab('cl', clusterFacts, s)

		then:
		fake.calls.size() == calls

		when: 'after the Retry-After time'
		now += 240_000L
		service.clusterTab('cl', clusterFacts, s)

		then:
		fake.calls.size() > calls
		!service.clusterTab('cl', clusterFacts, s).notes.any { it.startsWith('The last DSCC refresh failed') }
	}

	def "retry time: Retry-After seconds or date, else ratelimit-reset, capped at 15 minutes"() {
		given:
		DsccClient c = client()

		expect:
		c.retryAfterMs(new HttpResult(429, '', ['Retry-After': ['120']])) == 120_000L
		c.retryAfterMs(new HttpResult(429, '', ['ratelimit-reset': ['30']])) == 30_000L
		c.retryAfterMs(new HttpResult(429, '', ['RateLimit-Reset': ['30']])) == 30_000L
		c.retryAfterMs(new HttpResult(429, '', ['Retry-After': ['99999']])) == DsccClient.MAX_RETRY_AFTER_MS
		c.retryAfterMs(new HttpResult(429, '', ['Retry-After': ['Thu, 01 Jan 1970 00:02:01 GMT']])) == 120_000L   // clock is 1 s
		c.retryAfterMs(new HttpResult(429, '', ['Retry-After': ['soon']])) == null
		c.retryAfterMs(new HttpResult(429, '')) == null
	}

	def "a 429 without a wait header waits the usual minute"() {
		given:
		fake.override['/api/v1/storage-systems/device-type1'] = new HttpResult(429, '')
		DsccSettings s = Fixtures.settings()
		service.clusterTab('cl', clusterFacts, s)
		int calls = fake.calls.size()
		fake.override.clear()

		when:
		now += 30_000L
		service.clusterTab('cl', clusterFacts, s)

		then:
		fake.calls.size() == calls

		when:
		now += 31_000L
		service.clusterTab('cl', clusterFacts, s)

		then:
		fake.calls.size() > calls
	}

	// ---- D8: index timing --------------------------------------------------------------------------

	def "the index stays fresh for the TTL counted from the end of its build"() {
		given: 'every GET takes 20 s on the test clock'
		DsccSettings s = Fixtures.settings()
		fake.onGet = { String url -> now += 20_000L }
		service.clusterTab('cl', clusterFacts, s)
		long finished = now
		assert service.currentIndex() != null && finished - service.currentIndex().builtAt > 60_000L
		int calls = fake.calls.size()

		when:
		now = finished + DsccService.INDEX_TTL_MS - 1
		service.clusterTab('cl', clusterFacts, s)

		then:
		fake.calls.size() == calls

		when:
		now = finished + DsccService.INDEX_TTL_MS + 1
		service.clusterTab('cl', clusterFacts, s)

		then:
		fake.calls.size() > calls
	}

	def "after a read stopped by its time budget, the next one waits the full TTL"() {
		given:
		DsccSettings s = Fixtures.settings()
		fake.onGet = { String url -> now += 4L * 60_000L }
		service.clusterTab('cl', clusterFacts, s)
		assert service.@indexError.contains('longer than 10 minutes')
		fake.onGet = null
		int calls = fake.calls.size()

		when:
		now += DsccService.INDEX_RETRY_AFTER_ERROR_MS + 1
		service.clusterTab('cl', clusterFacts, s)

		then:
		fake.calls.size() == calls

		when:
		now += DsccService.INDEX_TTL_MS
		service.clusterTab('cl', clusterFacts, s)

		then:
		fake.calls.size() > calls
	}

	// ---- D6: settings change -------------------------------------------------------------------------

	def "a build for the old settings stops and writes neither its index nor its error"() {
		given: 'an executor that holds the build until the test runs it'
		List<Runnable> held = []
		DsccService svc = new DsccService({ DsccSettings x -> fake }, { now }, new HoldingExecutor(held), new DirectExecutor())
		svc.requestIndex(Fixtures.settings())
		assert held.size() == 1

		when: 'the settings change, then the old build runs'
		svc.clientFor(Fixtures.settings([dsccClientSecret: 'other']))
		held.remove(0).run()

		then:
		svc.currentIndex() == null
		svc.@indexError == null
		fake.calls.every { !it.startsWith('GET') }

		when: 'the new settings can start their own build at once'
		svc.requestIndex(Fixtures.settings([dsccClientSecret: 'other']))
		held.remove(0).run()

		then:
		svc.currentIndex() != null
	}

	def "a replaced client's build blocked in DSCC doesn't hold up the new client's build, and never writes"() {
		given: 'the old settings answer only when the test lets them; the new ones at once'
		CountDownLatch inCall = new CountDownLatch(1)
		CountDownLatch release = new CountDownLatch(1)
		FakeDscc stuck = new FakeDscc(onGet: { String url -> inCall.countDown(); release.await(20, TimeUnit.SECONDS) })
		FakeDscc quick = new FakeDscc()
		DsccSettings oldS = Fixtures.settings()
		DsccSettings newS = Fixtures.settings([dsccClientSecret: 'other'])
		DsccService svc = new DsccService({ DsccSettings x -> x.connectionKey() == oldS.connectionKey() ? stuck : quick }, { System.currentTimeMillis() })

		when:
		svc.requestIndex(oldS)
		assert inCall.await(10, TimeUnit.SECONDS)
		svc.requestIndex(newS)
		long until = System.currentTimeMillis() + 10_000L
		while (svc.currentIndex() == null && System.currentTimeMillis() < until) Thread.sleep(20)
		DsccIndex fresh = svc.currentIndex()

		then: 'the new build finished while the old one was still blocked'
		fresh != null
		inCall.count == 0 && release.count == 1

		when: 'the old build gets its answer and ends'
		release.countDown()
		Thread.sleep(500)

		then: 'it wrote nothing'
		svc.currentIndex().is(fresh)
		svc.@indexError == null
		stuck.calls.count { it.startsWith('GET') } == 1

		cleanup:
		svc.shutdown()
	}

	// ---- D1: the memo ----------------------------------------------------------------------------------

	def "concurrent callers of one key wait for a single read"() {
		given:
		DsccService svc = new DsccService({ DsccSettings x -> fake }, { System.currentTimeMillis() }, new DirectExecutor(), new DirectExecutor())
		AtomicInteger reads = new AtomicInteger()
		CountDownLatch inRead = new CountDownLatch(1)
		CountDownLatch release = new CountDownLatch(1)
		Closure<Object> read = { reads.incrementAndGet(); inRead.countDown(); release.await(10, TimeUnit.SECONDS); 'facts' }
		List<Object> got = Collections.synchronizedList([])

		when:
		Thread first = Thread.start { got << svc.remember('k', 60_000L, read) }
		assert inRead.await(10, TimeUnit.SECONDS)
		List<Thread> others = (1..4).collect { Thread.start { got << svc.remember('k', 60_000L, read) } }
		Thread.sleep(200)
		release.countDown()
		([first] + others)*.join(10_000L)

		then:
		reads.get() == 1
		got == ['facts'] * 5
	}

	def "a failed read reaches its waiters and is not kept"() {
		given:
		int reads = 0

		when:
		service.remember('k', 60_000L) { reads++; throw new IllegalStateException('morpheus down') }

		then:
		thrown(IllegalStateException)

		expect:
		service.remember('k', 60_000L) { reads++; 'ok' } == 'ok'
		reads == 2
	}

	def "when the leader's read fails, a waiter on another thread gets the same failure"() {
		given:
		DsccService svc = new DsccService({ DsccSettings x -> fake }, { System.currentTimeMillis() }, new DirectExecutor(), new DirectExecutor())
		CountDownLatch inRead = new CountDownLatch(1)
		CountDownLatch release = new CountDownLatch(1)
		AtomicInteger reads = new AtomicInteger()
		Closure<Object> read = { reads.incrementAndGet(); inRead.countDown(); release.await(10, TimeUnit.SECONDS); throw new IllegalStateException('morpheus down') }
		List<Throwable> errors = Collections.synchronizedList([])

		when:
		Thread leader = Thread.start { try { svc.remember('k', 60_000L, read) } catch (Throwable e) { errors << e } }
		assert inRead.await(10, TimeUnit.SECONDS)
		Thread waiter = Thread.start { try { svc.remember('k', 60_000L, read) } catch (Throwable e) { errors << e } }
		Thread.sleep(200)
		release.countDown()
		[leader, waiter]*.join(10_000L)

		then:
		reads.get() == 1
		errors.size() == 2
		errors.every { it instanceof IllegalStateException && it.message == 'morpheus down' }
	}

	def "a null result is not kept"() {
		given:
		int reads = 0

		expect:
		service.remember('k', 60_000L) { reads++; null } == null
		service.remember('k', 60_000L) { reads++; null } == null
		reads == 2
	}

	def "over the limit, expired entries go first, then the oldest; the newest stay"() {
		given:
		(1..DsccService.MEMO_MAX).each { service.remember("short:${it}".toString(), 1_000L) { 'x' } }
		now += 5_000L
		service.remember('cluster', 60_000L) { 'costly' }
		now += 1L

		when: 'one more entry passes the limit: the expired ones go, the cluster entry stays'
		service.remember('one-more', 60_000L) { 'y' }

		then:
		service.memoSize() == 2
		service.remember('cluster', 60_000L) { 'read again' } == 'costly'

		when: 'all fresh: the oldest go'
		(1..DsccService.MEMO_MAX).each { now += 1L; service.remember("fresh:${it}".toString(), 60_000L) { 'z' } }

		then:
		service.memoSize() <= DsccService.MEMO_MAX
		service.memoSize() >= (int) (DsccService.MEMO_MAX * 0.9)
		service.remember("fresh:${DsccService.MEMO_MAX}".toString(), 60_000L) { 'read again' } == 'z'
		service.remember('cluster', 60_000L) { 'read again' } == 'read again'
	}

	def "an entry read late in its life is served and refreshed once in the background; past its TTL it is read again"() {
		given:
		int reads = 0
		Closure<Object> read = { "v${++reads}".toString() }
		assert service.remember('c', 60_000L, 45_000L, read) == 'v1'

		when: 'before the refresh age: no refresh'
		now += 44_000L

		then:
		service.remember('c', 60_000L, 45_000L, read) == 'v1'
		reads == 1

		when: 'after it: the current value now, a fresh one next time'
		now += 2_000L
		Object served = service.remember('c', 60_000L, 45_000L, read)

		then:
		served == 'v1'
		reads == 2
		service.remember('c', 60_000L, 45_000L, read) == 'v2'

		when: 'past the TTL of the refreshed entry: read on the caller thread, as before'
		now += 60_000L

		then:
		service.remember('c', 60_000L, 45_000L, read) == 'v3'
	}

	def "a refused background refresh leaves no read in flight"() {
		given:
		DsccService svc = new DsccService({ DsccSettings x -> fake }, { now }, new DirectExecutor(), new RejectingExecutor())
		int reads = 0
		Closure<Object> read = { "v${++reads}".toString() }
		svc.remember('c', 60_000L, 45_000L, read)

		when:
		now += 50_000L
		Object served = svc.remember('c', 60_000L, 45_000L, read)
		now += 20_000L

		then:
		served == 'v1'
		svc.remember('c', 60_000L, 45_000L, read) == 'v2'
	}
}

/** Keeps submitted tasks for the test to run later. */
class HoldingExecutor extends AbstractExecutorService {
	final List<Runnable> held
	HoldingExecutor(List<Runnable> held) { this.held = held }
	void execute(Runnable r) { held << r }
	void shutdown() { }
	List<Runnable> shutdownNow() { [] }
	boolean isShutdown() { false }
	boolean isTerminated() { false }
	boolean awaitTermination(long t, TimeUnit u) { true }
}

/** Refuses every task, like a full queue. */
class RejectingExecutor extends AbstractExecutorService {
	void execute(Runnable r) { throw new RejectedExecutionException('full') }
	void shutdown() { }
	List<Runnable> shutdownNow() { [] }
	boolean isShutdown() { false }
	boolean isTerminated() { false }
	boolean awaitTermination(long t, TimeUnit u) { true }
}
