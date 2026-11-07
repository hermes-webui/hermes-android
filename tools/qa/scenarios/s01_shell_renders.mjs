// Issues #5, #59, #110: the app must never render a blank or zero-height main pane.
export default {
  id: 's01',
  title: 'Shell renders (not blank / not collapsed)',
  async run({ cdp }) {
    const m = await cdp.evaluate(`(function(){
      var main = document.querySelector('.main');
      var r = main ? main.getBoundingClientRect() : null;
      return {
        bodyH: document.body.getBoundingClientRect().height,
        text: (document.body.innerText || '').length,
        mainH: r ? r.height : -1,
        mainW: r ? r.width : -1,
        vh: parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--vh')) || 0,
        innerH: innerHeight
      };
    })()`);
    return [
      { name: 'body has height', pass: m.bodyH > 0, detail: `bodyH=${m.bodyH}` },
      { name: 'page has visible text', pass: m.text > 0, detail: `chars=${m.text}` },
      { name: '.main pane has size', pass: m.mainH > 0 && m.mainW > 0, detail: `mainH=${m.mainH} mainW=${m.mainW}` },
      { name: 'viewport polyfill set --vh', pass: m.vh > 0, detail: `--vh=${m.vh}px innerHeight=${m.innerH}` },
    ];
  },
};
