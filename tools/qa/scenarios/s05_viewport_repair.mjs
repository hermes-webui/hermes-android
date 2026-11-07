// Viewport collapse repair contract (AGENTS.md): a collapsed generic block is repaired, the
// approval/clarify cards and composer anchors are excluded, stale repair on an excluded element
// is cleared, and the floating-prompt ancestors can never clip.
const ATTR = 'data-hermes-android-vh-repaired';

export default {
  id: 's05',
  title: 'Viewport collapse repair and exclusions',
  async run({ cdp }) {
    const out = [];
    try {
      await cdp.evaluate(`(function(){
        function mk(id, cls) {
          var d = document.createElement('div');
          d.id = id;
          d.className = cls;
          d.style.cssText = 'height:0;width:400px;position:static';
          var c = document.createElement('div');
          c.style.height = '500px';
          c.innerHTML = '<button>x</button>';
          d.appendChild(c);
          document.body.appendChild(d);
        }
        mk('__qa_generic', 'qa-generic');
        mk('__qa_card', 'approval-card');
        return 0;
      })()`);
      const repaired = await cdp.waitFor(`document.getElementById('__qa_generic').hasAttribute('${ATTR}')`, 5000);
      const h = await cdp.evaluate(`document.getElementById('__qa_generic').getBoundingClientRect().height`);
      out.push({ name: 'collapsed generic block is repaired', pass: !!repaired && h > 0, detail: `height=${Math.round(h)}px` });
      const cardRepaired = await cdp.evaluate(`document.getElementById('__qa_card').hasAttribute('${ATTR}')`);
      out.push({ name: '.approval-card is NOT repaired', pass: cardRepaired === false, detail: `repaired=${cardRepaired}` });

      await cdp.evaluate(`(function(){
        var c = document.getElementById('__qa_card');
        c.setAttribute('${ATTR}', '1');
        c.style.height = '123px';
        var i = document.createElement('i');
        i.id = '__qa_poke';
        document.body.appendChild(i);
        return 0;
      })()`);
      const cleared = await cdp.waitFor(`!document.getElementById('__qa_card').hasAttribute('${ATTR}')`, 5000);
      out.push({ name: 'stale repair on excluded element is cleared', pass: !!cleared, detail: `cleared=${!!cleared}` });
      const retained = await cdp.evaluate(`document.getElementById('__qa_generic').hasAttribute('${ATTR}')`);
      out.push({ name: 'repair on generic block is retained', pass: retained === true, detail: `retained=${retained}` });
    } finally {
      await cdp.evaluate(`['__qa_generic', '__qa_card', '__qa_poke'].forEach(function(i){
        var e = document.getElementById(i);
        if (e) e.remove();
      }); 0`);
    }
    const ov = await cdp.evaluate(`(function(){
      var f = document.querySelector('.composer-flyout'), w = document.querySelector('.composer-wrap');
      return { fly: f && getComputedStyle(f).overflow, wrap: w && getComputedStyle(w).overflow };
    })()`);
    out.push({
      name: 'composer-flyout/wrap never clip (#80)',
      pass: ov.fly === 'visible' && ov.wrap === 'visible',
      detail: `flyout=${ov.fly} wrap=${ov.wrap}`,
    });
    return out;
  },
};
