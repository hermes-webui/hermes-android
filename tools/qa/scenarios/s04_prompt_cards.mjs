// Approval/clarify prompt cards stay reachable on the real WebUI DOM (issue #80/#150).
// DOM-state only: never clicks, never calls respond*/show* functions, never touches a conversation.
const REPORT = (sel) => `(function(){
  var ATTR='data-hermes-android-vh-repaired';
  var card=document.querySelector(${JSON.stringify(sel)}); var inner=card.querySelector('.approval-inner, .clarify-inner');
  var tb=document.querySelector('.app-titlebar'); var wrap=document.querySelector('.composer-wrap'); var fly=document.querySelector('.composer-flyout');
  var vv=window.visualViewport; var vvH=vv&&vv.height>0?vv.height:Math.max(window.innerHeight,document.documentElement.clientHeight);
  var vvTop=vv?Math.max(0,vv.offsetTop||0):0; var vvBottom=vvTop+vvH;
  var tbBottom=tb?tb.getBoundingClientRect().bottom:0; var promptTop=Math.max(tbBottom,vvTop);
  var cr=card.getBoundingClientRect(); var cap=parseFloat(getComputedStyle(card).maxHeight);
  var f=[]; function fail(m){f.push(m);} function desc(e){return e?e.tagName+'#'+e.id+'.'+e.className:'null';}
  if(!(cr.height>0))fail('card has no height');
  if(cr.top<promptTop+8-1.5)fail('card top '+cr.top+' above '+(promptTop+8));
  if(cr.bottom>vvBottom+1)fail('card bottom '+cr.bottom+' below visual viewport '+vvBottom);
  if(!(cap>0)||cap>420)fail('cap '+cap);
  [wrap,fly].forEach(function(e){ if(!e)return; var s=getComputedStyle(e);
    if(s.overflowY!=='visible'||s.overflowX!=='visible')fail('scroll container on '+e.className);
    if(e.hasAttribute(ATTR))fail('generic repair on '+e.className);});
  if(card.hasAttribute(ATTR))fail('generic repair on card');
  var box=document.querySelector('.composer-box'); var boxTop=box?box.getBoundingClientRect().top:vvBottom;
  var n=0; var els=card.querySelectorAll('button, input');
  for(var k=0;k<els.length;k++){var el=els[k]; if(getComputedStyle(el).display==='none')continue;
    var ib=inner.getBoundingClientRect(); var r=el.getBoundingClientRect(); var vb=Math.min(ib.bottom,boxTop,vvBottom);
    if(r.bottom>vb-1)inner.scrollTop+=r.bottom-(vb-1); else if(r.top<ib.top)inner.scrollTop-=ib.top-r.top;
    ib=inner.getBoundingClientRect(); r=el.getBoundingClientRect(); vb=Math.min(ib.bottom,boxTop,vvBottom);
    var lb=el.id||el.getAttribute('data-choice')||el.className;
    if(!(r.width>0&&r.height>0)){fail(lb+' no size');continue;} n++;
    var ins=Math.min(6,r.width/4,r.height/4);
    var pts=[[r.left+r.width/2,r.top+r.height/2],[r.left+ins,r.top+ins],[r.right-ins,r.top+ins],[r.left+ins,r.bottom-ins],[r.right-ins,r.bottom-ins]];
    pts.forEach(function(p,i){ if(p[0]<ib.left||p[0]>ib.right||p[1]<ib.top||p[1]>vb){fail(lb+' pt'+i+' outside the unobscured card');return;}
      if(p[1]<tbBottom||p[1]>vvBottom){fail(lb+' pt'+i+' behind titlebar/IME');return;}
      var h=document.elementFromPoint(p[0],p[1]); if(!h||!(h===el||el.contains(h)))fail(lb+' pt'+i+' hit '+desc(h));});}
  return {failures:f,tested:n};})()`;

const SAVE = `(function(){
  window.__qaPromptSave = ['approvalCard','clarifyCard'].map(function(id){
    var c=document.getElementById(id);
    return c ? {id:id,html:c.innerHTML,hidden:c.hidden,aria:c.getAttribute('aria-hidden'),inert:c.hasAttribute('inert'),
                cls:c.className,style:c.getAttribute('style')} : null; });
  return window.__qaPromptSave.map(function(s){return s&&{id:s.id,active:!s.hidden||/visible/.test(s.cls)};});
})()`;

const SHOW = `(function(){
  function show(c){c.hidden=false;c.setAttribute('aria-hidden','false');c.removeAttribute('inert');c.classList.add('visible');}
  document.getElementById('approvalDesc').textContent='QA synthetic approval (not real)';
  document.getElementById('approvalCmd').textContent='echo qa';
  document.getElementById('clarifyQuestion').textContent='QA synthetic question (not real)';
  var box=document.getElementById('clarifyChoices'); box.innerHTML='';
  for(var i=1;i<=8;i++){var b=document.createElement('button'); b.type='button'; b.className='clarify-choice'; b.setAttribute('data-choice','qa'+i);
    b.innerHTML='<span class="clarify-choice-badge">'+i+'</span><span class="clarify-choice-text">QA option '+i+'</span>'; box.appendChild(b);}
  var o=document.createElement('button'); o.type='button'; o.className='clarify-choice other'; o.setAttribute('data-choice','other');
  o.innerHTML='<span class="clarify-choice-badge other">.</span><span class="clarify-choice-text">Other</span>'; box.appendChild(o);
  return 0;})()`;

const RESTORE = `(function(){
  (window.__qaPromptSave||[]).forEach(function(s){ if(!s)return; var c=document.getElementById(s.id); if(!c)return;
    c.classList.remove('visible'); c.innerHTML=s.html; c.hidden=s.hidden; c.className=s.cls;
    if(s.aria===null)c.removeAttribute('aria-hidden'); else c.setAttribute('aria-hidden',s.aria);
    if(s.inert)c.setAttribute('inert',''); else c.removeAttribute('inert');
    if(s.style===null)c.removeAttribute('style'); else c.setAttribute('style',s.style); });
  delete window.__qaPromptSave; return 0;})()`;

export default {
  id: 's04',
  title: 'Approval/clarify cards reachable on the real DOM',
  async run({ cdp, sleep }) {
    const out = [];
    const state = await cdp.evaluate(SAVE);
    if (state.some((s) => !s)) {
      return [{ name: 'prompt card markup present', pass: false, detail: 'missing #approvalCard/#clarifyCard (upstream markup drift)' }];
    }
    if (state.some((s) => s.active)) {
      return [{ name: 'no real prompt active', pass: true, detail: 'SKIPPED: a real prompt is showing; not touching it' }];
    }
    try {
      await cdp.evaluate(SHOW);
      for (const [id, sel] of [['approvalCard', '.approval-card.visible'], ['clarifyCard', '.clarify-card.visible']]) {
        await cdp.evaluate(`(function(){var c=document.getElementById('${id}');c.hidden=false;c.setAttribute('aria-hidden','false');c.removeAttribute('inert');c.classList.add('visible');return 0;})()`);
        await cdp.waitFor(`getComputedStyle(document.querySelector('#${id} > div')).opacity==='1'`, 5000);
        await sleep(500); // let the shim's MutationObserver rescan settle
        const still = await cdp.evaluate(`document.getElementById('${id}').classList.contains('visible')`);
        if (!still) { out.push({ name: `${id} stays shown`, pass: false, detail: 'WebUI hid the synthetic card (race); result inconclusive' }); continue; }
        const r = await cdp.evaluate(REPORT(sel));
        out.push({ name: `${id}: every control hit-testable, inside viewport and below titlebar, no clipping`,
          pass: r.failures.length === 0 && r.tested >= 5, detail: `tested=${r.tested} failures=${r.failures.slice(0, 3).join('; ')}` });
        await cdp.evaluate(`(function(){var c=document.getElementById('${id}');c.classList.remove('visible');c.setAttribute('aria-hidden','true');c.setAttribute('inert','');c.hidden=true;return 0;})()`);
      }
    } finally {
      await cdp.evaluate(RESTORE);
    }
    const restored = await cdp.evaluate(`['approvalCard','clarifyCard'].every(function(id){
      var c=document.getElementById(id); return c.hidden && !c.classList.contains('visible'); })`);
    out.push({ name: 'page restored (cards hidden again)', pass: restored === true, detail: `restored=${restored}` });
    return out;
  },
};
