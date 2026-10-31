package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.HandlebarsRenderer
import groovy.util.logging.Slf4j

/**
 * dscc-morpheus: HPE storage array data from HPE GreenLake Data Services (DSCC) on Morpheus pages.
 *
 * Phase 1 (read-only): an "HPE Storage" tab on cluster, hypervisor host and VM (server and instance) pages that maps
 * each SAN LUN Morpheus uses to its array volume by WWN, with the volume's size, use and state and the array's
 * health and capacity, linking to DSCC. Settings are in Administration > Integrations > Plugins.
 *
 * Community project: not affiliated with, endorsed or supported by HPE.
 */
@Slf4j
class DsccMorpheusPlugin extends Plugin {

	static final String CODE = 'dscc-morpheus-plugin'
	static final String PERMISSION_CODE = 'dscc-morpheus-view'

	/** Not final so tests can give it a fake DSCC. */
	DsccService service = new DsccService()

	@Override
	String getCode() { CODE }

	@Override
	void initialize() {
		this.setName('dscc-morpheus')
		this.setDescription('Unofficial community plugin (not an HPE product): shows HPE storage array data from DSCC for the LUNs Morpheus uses')
		this.setAuthor('dscc-morpheus community project')
		this.setSourceCodeLocationUrl('https://github.com/grahamfairbank/dscc-morpheus-plugin')
		this.setPermissions([
			new Permission('dscc-morpheus: View HPE Storage tab', PERMISSION_CODE, [Permission.AccessType.none, Permission.AccessType.read])
		])
		this.registerProvider(new DsccClusterTabProvider(this, morpheus))
		this.registerProvider(new DsccServerTabProvider(this, morpheus))
		this.registerProvider(new DsccInstanceTabProvider(this, morpheus))
		this.registerProvider(new DsccUiLoaderProvider(this, morpheus))
		// Same as com-morpheus-plugin (its HLD D9): the plugin brings its own renderer. Harmless without routes, and
		// it keeps the plugin loadable if routes are added later.
		this.setRenderer(new HandlebarsRenderer('renderer', this.getClassLoader() ?: this.class.classLoader))
	}

	@Override
	void onDestroy() {
		service.shutdown()
	}

	/** Current settings, read on every use so changes apply without reloading the plugin. */
	DsccSettings loadSettings() {
		try {
			return DsccSettings.fromJson(morpheus.getSettings(this).blockingGet())
		} catch (Throwable e) {
			log.warn("dscc-morpheus: could not read plugin settings: ${e}")
			return DsccSettings.fromJson(null)
		}
	}

	static boolean canView(DsccSettings settings, User user) {
		if (!settings.requirePermission) return true
		String level = user?.permissions?.get(PERMISSION_CODE)
		return level in ['read', 'full']
	}

	MorpheusReader reader() { new MorpheusReader(morpheus) }

	private final Map<String, Long> hiddenLogged = new java.util.concurrent.ConcurrentHashMap<>()

	/** Logs (once per 10 minutes per page) why a tab is hidden, and returns false. */
	boolean hidden(String what, String why) {
		long now = System.currentTimeMillis()
		Long last = hiddenLogged[what]
		if (last == null || now - last > 600_000L) {
			hiddenLogged[what] = now
			if (hiddenLogged.size() > 2000) hiddenLogged.clear()
			log.info("dscc-morpheus: no HPE Storage tab for ${what}: ${why}")
		}
		return false
	}

	/** Hosts, datastores and VMs of a cluster, read once a minute at most and shared by all its tabs. */
	Map clusterFacts(ComputeServerGroup cluster) {
		if (cluster?.id == null) return [hosts: [], datastores: [], guests: []]
		return (Map) service.remember("cluster:${cluster.id}".toString(), DsccService.FACTS_TTL_MS) {
			MorpheusReader r = reader()
			Long owner = MorpheusReader.safe { cluster.owner?.id } as Long
			List<ComputeServer> hostServers = r.clusterHosts(cluster)
			List<HostFacts> hosts = hostServers.collect { r.host(it) }
			List<DatastoreFacts> ds = r.clusterDatastores(cluster.id)
			List<GuestFacts> guests = r.guestsOn(hostServers*.id, owner).collect { r.guest(it) }
			[hosts: hosts, datastores: ds, guests: guests]
		}
	}

	/** A datastore by id, remembered for a minute (VM tabs look up the datastores of their disks). */
	DatastoreFacts datastore(Long id) {
		if (id == null) return null
		return (DatastoreFacts) service.remember("ds:${id}".toString(), DsccService.FACTS_TTL_MS) { reader().datastoreById(id) }
	}

	Map<Long, DatastoreFacts> datastoresFor(List<GuestFacts> guests) {
		Map<Long, DatastoreFacts> out = [:]
		guests.each { g -> g.disks.each { d -> if (d.datastoreId != null && !out.containsKey(d.datastoreId)) out[d.datastoreId] = datastore(d.datastoreId) } }
		return out.findAll { k, v -> v != null }
	}

	/** True when any of these VMs has a disk on an array LUN, directly or through a GFS2 datastore. */
	boolean anyArrayDisk(List<GuestFacts> guests) {
		Map<Long, DatastoreFacts> ds = datastoresFor(guests)
		return guests.any { g -> g.disks.any { d -> StorageMap.backing(d, ds).kind in ['lun', 'file'] && WwnMatcher.isHpeArray(StorageMap.backing(d, ds).lunId as String) } }
	}

	@Override
	List<OptionType> getSettings() {
		int order = 0
		return [
			new OptionType(name: 'DSCC region', code: 'dscc-morpheus-region', fieldName: DsccSettings.REGION,
				fieldLabel: 'DSCC region', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				defaultValue: DsccSettings.DEFAULT_REGION, placeHolderText: DsccSettings.DEFAULT_REGION,
				helpText: 'us1, eu1 or jp1: the Data Services region where your arrays are onboarded (or a full https:// API URL).'),
			new OptionType(name: 'Client ID', code: 'dscc-morpheus-client-id', fieldName: DsccSettings.CLIENT_ID,
				fieldLabel: 'API client ID', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				helpText: 'From a GreenLake personal API client (IAM and administration > API access; service: GreenLake platform and most services), created in the workspace that holds the arrays. It acts as the GreenLake user who created it for everyone on this appliance, so use a dedicated account with a read-only Data Services role. Leave blank to hide the tab.'),
			new OptionType(name: 'Client secret', code: 'dscc-morpheus-client-secret', fieldName: DsccSettings.CLIENT_SECRET,
				fieldLabel: 'API client secret', inputType: OptionType.InputType.PASSWORD, required: false, displayOrder: order++,
				helpText: 'From the same GreenLake personal API client. GreenLake shows it only once, when the client is created.'),
			new OptionType(name: 'Workspace ID', code: 'dscc-morpheus-workspace-id', fieldName: DsccSettings.WORKSPACE_ID,
				fieldLabel: 'GreenLake workspace ID', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				helpText: 'The 32-character ID from IAM and administration > Workspace details (also the middle of the client\'s Issuer URL). Without it DSCC can answer "failed to authorize" even with a valid client.'),
			new OptionType(name: 'Token URL', code: 'dscc-morpheus-token-url', fieldName: DsccSettings.TOKEN_URL,
				fieldLabel: 'Token URL override', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				helpText: 'Optional. Leave blank to detect it automatically.'),
			new OptionType(name: 'DSCC UI URL', code: 'dscc-morpheus-ui-url', fieldName: DsccSettings.UI_URL,
				fieldLabel: 'DSCC console URL', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				placeHolderText: 'https://console-us1.data.cloud.hpe.com',
				helpText: 'Optional. Base of the "Open in DSCC" links. Blank picks it from the region.'),
			new OptionType(name: 'Proxy URL', code: 'dscc-morpheus-proxy-url', fieldName: DsccSettings.PROXY_URL,
				fieldLabel: 'HTTP proxy for GreenLake', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				placeHolderText: 'http://proxy.example.com:3128',
				helpText: 'Optional. Proxy the Morpheus appliance uses to reach *.data.cloud.hpe.com and the GreenLake sign-in hosts (no authentication).'),
			new OptionType(name: 'Require permission', code: 'dscc-morpheus-require-permission', fieldName: DsccSettings.REQUIRE_PERMISSION,
				fieldLabel: 'Only show to roles with the dscc-morpheus permission', inputType: OptionType.InputType.CHECKBOX,
				required: false, displayOrder: order++, defaultValue: 'off',
				helpText: 'When on, only roles granted "dscc-morpheus: View HPE Storage tab" see the tab. When off, everyone who can see the page can.'),
			new OptionType(name: 'Show always', code: 'dscc-morpheus-show-always', fieldName: DsccSettings.SHOW_ALWAYS,
				fieldLabel: 'Show the tab even without HPE array LUNs (diagnostics)', inputType: OptionType.InputType.CHECKBOX,
				required: false, displayOrder: order++, defaultValue: 'off',
				helpText: 'For troubleshooting: shows the tab on every cluster, hypervisor host and VM, with what the plugin sees.')
		]
	}
}
