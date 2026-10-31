/* dscc-morpheus: copy buttons for LUN ids on the HPE Storage tabs.
 * Loaded on every page by DsccUiLoaderProvider (inline scripts in tab HTML can be dropped by the Morpheus UI).
 * The tabs arrive later by AJAX, so one delegated click handler serves them all. It only copies text that
 * looks like a LUN id (16 or 32 hex digits) to the clipboard: no requests, no page changes. */
(function () {
  'use strict';
  if (window.__dsccMorpheus) return;
  window.__dsccMorpheus = true;
  var ID = /^[0-9a-f]{16}$|^[0-9a-f]{32}$/;

  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    return ok ? Promise.resolve() : Promise.reject(new Error('copy failed'));
  }

  function copy(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).catch(function () { return fallbackCopy(text); });
    }
    return fallbackCopy(text);
  }

  document.addEventListener('click', function (ev) {
    var btn = ev.target && ev.target.closest ? ev.target.closest('.dscc-morpheus button.vc-copy') : null;
    if (!btn) return;
    ev.preventDefault();
    var text = (btn.getAttribute('data-dscc-copy') || '').trim().toLowerCase();
    if (!ID.test(text)) return;
    var title = btn.getAttribute('title');
    copy(text).then(function () {
      btn.classList.add('vc-copied');
      btn.setAttribute('title', 'Copied');
    }, function () {
      btn.setAttribute('title', 'Copy failed: select the id in the tooltip instead');
    }).then(function () {
      setTimeout(function () { btn.classList.remove('vc-copied'); btn.setAttribute('title', title); }, 1500);
    });
  });
})();
