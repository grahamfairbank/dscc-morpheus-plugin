package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.core.AbstractInstanceTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.Instance
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/** "HPE Storage" tab on instance pages: the guest view for the instance's VM(s). */
@Slf4j
class DsccInstanceTabProvider extends AbstractInstanceTabProvider {

	DsccMorpheusPlugin plugin
	MorpheusContext morpheus

	DsccInstanceTabProvider(DsccMorpheusPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }
	@Override String getCode() { 'dscc-morpheus-instance-tab' }
	@Override String getName() { 'HPE Storage' }

	@Override
	Boolean show(Instance instance, User user, Account account) {
		if (instance?.id == null) return false
		DsccSettings s = plugin.loadSettings()
		if (!(s.configured && DsccMorpheusPlugin.canView(s, user))) return false
		try {
			Instance full = plugin.reader().fullInstance(instance)
			Long owner = MorpheusReader.safe { full.account?.id } as Long
			if (!MorpheusReader.mayViewPage(owner, MorpheusReader.viewer(user, account))) return plugin.hidden("instance ${instance.id}", "tenant (owner ${owner}, viewer ${MorpheusReader.safe { MorpheusReader.viewer(user, account)?.id }})")
			if (s.showAlways) return true
			return plugin.anyArrayDisk(guests(full)) ?: plugin.hidden("instance ${instance.id}", 'no disk on an HPE array LUN')
		} catch (Throwable e) {
			log.warn("dscc-morpheus: instance tab check for ${instance.id} failed: ${e}")
			return false
		}
	}

	@Override
	HTMLResponse renderTemplate(Instance instance) {
		DsccSettings s = plugin.loadSettings()
		Map model
		try {
			model = plugin.service.guestTab(instance.name, {
				List<GuestFacts> g = guests(plugin.reader().fullInstance(instance))
				[guests: g, datastores: plugin.datastoresFor(g)]
			}, s)
		} catch (Throwable e) {
			model = [state: 'error', error: DsccService.unexpected("instance tab for ${instance?.id}", e)]
		}
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = DsccView.withFlags(model)
		return getRenderer().renderTemplate('hbs/dscc-guest', vm)
	}

	List<GuestFacts> guests(Instance instance) {
		MorpheusReader r = plugin.reader()
		return r.instanceServers(instance).collect { r.guest(it) }
	}
}
