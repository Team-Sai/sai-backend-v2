const fs=require('fs');
const path=require('path');
const vm=require('vm');
const assert=require('assert/strict');
const dir=__dirname;
for(const name of ['batch-explained.html','presenter-guide.html']){
 const html=fs.readFileSync(path.join(dir,name),'utf8');
 const ids=[...html.matchAll(/\bid="([^"]+)"/g)].map(m=>m[1]);
 assert.equal(new Set(ids).size,ids.length,'duplicate IDs');
 for(const [,href] of html.matchAll(/href="([^"]+)"/g)){
  if(href.startsWith('#'))assert(ids.includes(href.slice(1)),`missing anchor: ${href}`);
  else assert(fs.existsSync(path.join(dir,href)),`missing relative link: ${href}`);
 }
 assert(!/<(?:script|link)[^>]+(?:src|href)="https?:/i.test(html),'external dependency');
 const stack=[];const voids=new Set(['meta','link','br','hr','img','input','wbr']);
 const markup=html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/g,'').replace(/<style\b[^>]*>[\s\S]*?<\/style>/g,'');
 for(const m of markup.matchAll(/<(\/?)([a-z][a-z0-9]*)\b[^>]*>/gi)){
  const tag=m[2].toLowerCase();if(voids.has(tag))continue;
  if(m[1])assert.equal(stack.pop(),tag,`unbalanced closing ${tag} in ${name}`);else stack.push(tag);
 }
 assert.equal(stack.length,0,'unclosed tags');
 if(name==='batch-explained.html'){
  assert.equal((html.match(/<section id=/g)||[]).length,13);
  assert.equal((html.match(/\/ FLOW [1-6]/g)||[]).length,6);
  assert.equal((html.match(/class="source"/g)||[]).length,18);
  for(const [card] of html.matchAll(/<details class="source" open>[\s\S]*?<\/details>/g))assert(card.includes('// [설명]'),'missing explanatory code comments');
  const narrative=html.replace(/<pre>[\s\S]*?<\/pre>/g,'');
  assert(!/뜻은 아닙니다|의미하지는 않습니다|의미는 아닙니다|것은 아닙니다/.test(narrative),'ambiguous wording');
  const buttons={};for(const id of ['presentation','sources','print'])buttons[id]={attrs:{},textContent:'',setAttribute(k,v){this.attrs[k]=String(v)},getAttribute(k){return this.attrs[k]}};
  buttons.sources.attrs['aria-expanded']='true';
  const details=Array.from({length:18},()=>({open:true}));const classes=new Set();let printed=false;
  const document={body:{classList:{toggle(c){if(classes.has(c)){classes.delete(c);return false}classes.add(c);return true}}},getElementById:id=>buttons[id],querySelectorAll:s=>s==='details.source'?details:[]};
  const sandbox={document,window:{print(){printed=true}},IntersectionObserver:class{observe(){}}};
  const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];vm.runInNewContext(script,sandbox);
  buttons.presentation.onclick();assert(classes.has('presentation'));buttons.presentation.onclick();assert(!classes.has('presentation'));
  buttons.sources.onclick();assert(details.every(d=>!d.open));buttons.sources.onclick();assert(details.every(d=>d.open));buttons.print.onclick();assert(printed);
 }
 console.log(`PASS ${name}: markup, anchors, local links, dependency checks`);
}
console.log('PASS presentation controls: presentation toggle, code expansion, print handler');
