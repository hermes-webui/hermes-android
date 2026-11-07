// Injected "Application Settings" entry: exactly one, directly after Settings, and self-healing.
const E = '[data-hermes-android-app-settings-entry="1"]';
const COUNT = `document.querySelectorAll('${E}').length`;
const PREV_LABEL = `(function(){
  var e = document.querySelector('${E}');
  var p = e && e.previousElementSibling;
  return p ? (p.getAttribute('aria-label') || p.title || p.getAttribute('data-tooltip') || '') : '';
})()`;
// Any DOM mutation re-triggers the injector's scan.
const POKE = `(function(){
  var i = document.createElement('i');
  document.body.appendChild(i);
  setTimeout(function(){ i.remove(); }, 50);
  return 0;
})()`;

export default {
  id: 's02',
  title: 'Application Settings sidebar entry',
  async run({ cdp }) {
    const out = [];
    const count = await cdp.evaluate(COUNT);
    out.push({ name: 'exactly one entry', pass: count === 1, detail: `count=${count}` });
    const prev = await cdp.evaluate(PREV_LABEL);
    out.push({ name: 'placed directly after Settings', pass: prev === 'Settings', detail: `previous="${prev}"` });
    const href = await cdp.evaluate(`(document.querySelector('${E}') || document.body).getAttribute('href')`);
    out.push({ name: 'opens native settings', pass: href === 'hermes://app/settings', detail: `href=${href}` });

    // Self-heal: delete the entry, then trigger a DOM mutation.
    await cdp.evaluate(`document.querySelector('${E}').remove(); ${POKE}`);
    const restored = await cdp.waitFor(`${COUNT} === 1 && ${PREV_LABEL} === 'Settings'`);
    out.push({ name: 'entry restored after removal', pass: !!restored, detail: `count=${await cdp.evaluate(COUNT)}` });

    // Self-heal: move the entry away from its anchor.
    await cdp.evaluate(`(function(){
      var e = document.querySelector('${E}');
      e.parentNode.insertBefore(e, e.parentNode.firstChild);
    })(); ${POKE}`);
    const replaced = await cdp.waitFor(`${COUNT} === 1 && ${PREV_LABEL} === 'Settings'`);
    out.push({ name: 'entry re-placed after being moved', pass: !!replaced, detail: `count=${await cdp.evaluate(COUNT)}` });
    return out;
  },
};
