package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * "HPE Storage" tab on cluster pages: the arrays serving the cluster, each GFS2 datastore's LUN and array volume,
 * integrated (per VM disk) LUNs grouped by datastore, and checks for LUNs some hosts can't see or DSCC doesn't list.
 */
@Slf4j
class DsccClusterTabProvider extends AbstractClusterTabProvider {

	DsccMorpheusPlugin plugin
	MorpheusContext morpheus

	DsccClusterTabProvider(DsccMorpheusPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }
	@Override String getCode() { 'dscc-morpheus-cluster-tab' }
	@Override String getName() { 'HPE Storage' }

	@Override
	Boolean show(ComputeServerGroup cluster, User user, Account account) {
		if (cluster?.id == null) return false
		DsccSettings s = plugin.loadSettings()
		if (!(s.configured && DsccMorpheusPlugin.canView(s, user) && MorpheusReader.mayView(MorpheusReader.safe { cluster.owner?.id } as Long, MorpheusReader.viewer(user, account)))) return false
		if (s.showAlways) {
			plugin.warmIndex(s)
			return true
		}
		try {
			// Hosts' LUNs and datastores decide; the VMs (the slow read) are read by the render only.
			Map f = plugin.clusterStorage(cluster)
			boolean shown = ((List<HostFacts>) f.hosts).any { h -> h.luns.any { WwnMatcher.isHpeArray(it.deviceId as String) } } ||
				((List<DatastoreFacts>) f.datastores).any { WwnMatcher.isHpeArray(it.lunId) }
			if (shown) plugin.warmIndex(s)
			return shown
		} catch (Throwable e) {
			log.warn("dscc-morpheus: cluster tab check for ${cluster.id} failed: ${e}")
			return false
		}
	}

	@Override
	HTMLResponse renderTemplate(ComputeServerGroup cluster) {
		DsccSettings s = plugin.loadSettings()
		Map model
		try {
			model = plugin.service.clusterTab(cluster.name, { plugin.clusterFacts(cluster) }, s)
		} catch (Throwable e) {
			model = [state: 'error', error: DsccService.unexpected("cluster tab for ${cluster?.id}", e)]
		}
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = DsccView.withFlags(model)
		return getRenderer().renderTemplate('hbs/dscc-cluster', vm)
	}
}
