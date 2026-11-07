// Issue #92: the shims key off upstream class names and ids. If WebUI renames one, the shim
// silently stops working and no Android test notices. This scenario is the drift alarm.
const REQUIRED = [
  ['.rail', 'sidebar rail (settings entry anchor)'],
  ['.rail-btn', 'rail buttons'],
  ['.app-titlebar', 'titlebar (approval/clarify cap uses it)'],
  ['.composer-wrap', 'composer wrapper (excluded from generic repair)'],
  ['.composer-flyout', 'composer flyout (approval/clarify anchor)'],
  ['.approval-card', 'approval prompt card'],
  ['.clarify-card', 'clarify prompt card'],
  ['#clarifyInput', 'clarify input (autofocus shim)'],
  ['.main', 'main pane'],
  ['.main-view', 'main views (showing-* layout)'],
];

export default {
  id: 's03',
  title: 'Shim selector drift guard',
  async run({ cdp }) {
    const out = [];
    for (const [sel, why] of REQUIRED) {
      const n = await cdp.evaluate(`document.querySelectorAll(${JSON.stringify(sel)}).length`);
      out.push({ name: `present: ${sel}`, pass: n >= 1, detail: n >= 1 ? why : `MISSING - ${why}` });
    }
    const msgs = await cdp.evaluate(`document.querySelectorAll('.messages, #messages, [data-testid="messages"]').length`);
    out.push({ name: 'present: messages container', pass: msgs >= 1, detail: `matches=${msgs}` });
    const settings = await cdp.evaluate(`Array.prototype.filter.call(document.querySelectorAll('button, a'), function(n){
      return (n.getAttribute('aria-label') || n.title || n.getAttribute('data-tooltip') || '') === 'Settings';
    }).length`);
    out.push({ name: 'present: control labelled "Settings"', pass: settings >= 1, detail: `matches=${settings}` });
    return out;
  },
};
