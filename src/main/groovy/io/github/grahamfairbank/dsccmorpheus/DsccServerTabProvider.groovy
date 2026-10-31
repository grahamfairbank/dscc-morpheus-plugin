package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.core.AbstractServerTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * "HPE Storage" tab on server pages. Two views:
 * - hypervisor host: every array LUN the host sees, with its array volume and what uses it;
 * - VM: one row per disk, with how it is stored (file on a GFS2 datastore, or its own LUN) and the backing volume.
 * Rendered server-side in one pass: no browser calls to DSCC and no plugin routes.
 */
@Slf4j
class DsccServerTabProvider extends AbstractServerTabProvider {

	DsccMorpheusPlugin plugin
	MorpheusContext morpheus

	DsccServerTabProvider(DsccMorpheusPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }
	@Override String getCode() { 'dscc-morpheus-server-tab' }
	@Override String getName() { 'HPE Storage' }

	@Override
	Boolean show(ComputeServer server, User user, Account account) {
		if (server?.id == null) return false
		DsccSettings s = plugin.loadSettings()
		if (!(s.configured && DsccMorpheusPlugin.canView(s, user))) return false
		try {
			MorpheusReader r = plugin.reader()
			// The server passed in can be bare (no owner, type or volumes): decide on the full record, loaded by id
			// (fullServer keeps it for the render that follows; the tenant check below runs on every call).
			ComputeServer s1 = plugin.fullServer(r, server)
			Long owner = MorpheusReader.ownerOf(s1)
			if (!MorpheusReader.mayViewPage(owner, MorpheusReader.viewer(user, account))) return plugin.hidden("server ${server.id}", "tenant (owner ${owner}, viewer ${MorpheusReader.safe { MorpheusReader.viewer(user, account)?.id }})")
			boolean shown
			if (s.showAlways) {
				shown = true
			} else if (MorpheusReader.isHost(s1)) {
				HostFacts h = plugin.hostOf(r, s1)
				shown = h.luns.any { WwnMatcher.isHpeArray(it.deviceId as String) } ?: plugin.hidden("host ${server.id}", "no HPE array LUN among ${h.devices.size()} devices")
			} else {
				shown = plugin.anyArrayDisk([plugin.guestOf(r, s1)]) ?: plugin.hidden("VM ${server.id}", 'no disk on an HPE array LUN')
			}
			if (shown) plugin.warmIndex(s)
			return shown
		} catch (Throwable e) {
			log.warn("dscc-morpheus: server tab check for ${server.id} failed: ${e}")
			return false
		}
	}

	@Override
	HTMLResponse renderTemplate(ComputeServer server) {
		DsccSettings s = plugin.loadSettings()
		Map model
		String template
		try {
			MorpheusReader r = plugin.reader()
			server = plugin.fullServer(r, server)
			if (MorpheusReader.isHost(server)) {
				template = 'hbs/dscc-host'
				model = plugin.service.hostTab(server.name, { hostFacts(r, server) }, s)
			} else {
				template = 'hbs/dscc-guest'
				model = plugin.service.guestTab(server.name, {
					List<GuestFacts> g = [plugin.guestOf(r, server)]
					[guests: g, datastores: plugin.datastoresFor(g)]
				}, s)
			}
		} catch (Throwable e) {
			template = template ?: 'hbs/dscc-guest'
			model = [state: 'error', error: DsccService.unexpected("server tab for ${server?.id}", e)]
		}
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = DsccView.withFlags(model)
		return getRenderer().renderTemplate(template, vm)
	}

	/** This host, plus its cluster's hosts, datastores and VMs (for "seen on" and "used by"). */
	Map hostFacts(MorpheusReader r, ComputeServer server) {
		HostFacts me = plugin.hostOf(r, server)
		ComputeServerGroup cluster = server.serverGroup
		if (cluster?.id != null) {
			Map f = plugin.clusterFacts(cluster)
			List<HostFacts> hosts = (List<HostFacts>) f.hosts
			if (!hosts.any { it.id == me.id }) hosts = hosts + [me]
			return [host: me, hosts: hosts, datastores: f.datastores, guests: f.guests]
		}
		Long owner = MorpheusReader.safe { server.account?.id } as Long
		return [host: me, hosts: [me], datastores: [], guests: r.guestsOn([server.id], owner).collect { r.guest(it) }]
	}
}
