package io.github.grahamfairbank.dsccmorpheus

import com.morpheusdata.core.AbstractGlobalUIComponentProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * Loads the plugin's small browser script (copy buttons) on every page for users who can see the tab.
 * Same pattern as com-morpheus-plugin: inline scripts in tab HTML can be dropped by the Morpheus UI, so the
 * script is a plugin asset included through a global UI component with the page's CSP nonce.
 */
@Slf4j
class DsccUiLoaderProvider extends AbstractGlobalUIComponentProvider {

	DsccMorpheusPlugin plugin
	MorpheusContext morpheus

	DsccUiLoaderProvider(DsccMorpheusPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override String getCode() { 'dscc-morpheus-ui-loader' }
	@Override String getName() { 'dscc-morpheus script loader' }
	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }

	@Override
	Boolean show(User user, Account account) {
		DsccSettings s = plugin.loadSettings()
		return s.configured && DsccMorpheusPlugin.canView(s, user)
	}

	@Override
	HTMLResponse renderTemplate(User user, Account account) {
		try {
			ViewModel<Map> vm = new ViewModel<>()
			vm.object = [version: plugin.version ?: 'dev']
			return getRenderer().renderTemplate('hbs/dscc-loader', vm)
		} catch (Throwable e) {
			log.warn("dscc-morpheus: script loader failed: ${e}")
			return HTMLResponse.success('')
		}
	}
}
