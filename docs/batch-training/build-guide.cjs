const fs=require('fs');
const path=require('path');
const esc=s=>s.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');
const inline=s=>esc(s).replace(/`([^`]+)`/g,'<code>$1</code>').replace(/\*\*([^*]+)\*\*/g,'<strong>$1</strong>');
const lines=fs.readFileSync(path.join(__dirname,'presenter-guide.md'),'utf8').split(/\r?\n/);
let out=[],para=[],list=null;
const flush=()=>{if(para.length){out.push('<p>'+inline(para.join(' '))+'</p>');para=[];}if(list){out.push('</'+list+'>');list=null;}};
for(let i=0;i<lines.length;i++){
 const l=lines[i];
 if(!l.trim()){flush();continue;}
 if(l.startsWith('|')){flush();let rows=[];while(i<lines.length&&lines[i].startsWith('|')){const cells=lines[i].split('|').slice(1,-1).map(s=>s.trim());if(!cells.every(s=>/^:?-+:?$/.test(s)))rows.push(cells);i++;}i--;out.push('<div class="tablewrap"><table>'+rows.map((r,j)=>'<tr>'+r.map(c=>`<${j?'td':'th'}>${inline(c)}</${j?'td':'th'}>`).join('')+'</tr>').join('')+'</table></div>');continue;}
 const h=l.match(/^(#{1,3}) (.*)$/);if(h){flush();out.push(`<h${h[1].length}>${inline(h[2])}</h${h[1].length}>`);continue;}
 const li=l.match(/^(?:- |\d+\. )(.*)$/);if(li){if(para.length)flush();const type=l.startsWith('-')?'ul':'ol';if(list!==type){flush();out.push('<'+type+'>');list=type;}out.push('<li>'+inline(li[1])+'</li>');continue;}
 if(list)flush();para.push(l);
}flush();
const presentation=fs.readFileSync(path.join(__dirname,'batch-explained.html'),'utf8');
const snippets=[...presentation.matchAll(/<details class="source" open>[\s\S]*?<\/details>/g)].map(m=>m[0]);
out.push('<h2>16. 주요 기능별 실제 코드와 설명 주석</h2><p>발표자료와 동일한 로컬 Java 코드 발췌입니다. 줄 번호는 원본 위치이며, [설명] 주석은 교육자료에만 추가했습니다. 각 코드 카드 제목에서 파일명을 확인할 수 있습니다.</p>'+snippets.join('\n'));
fs.writeFileSync(path.join(__dirname,'presenter-guide.html'),`<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SAI 배치 발표자 상세 가이드</title><style>body{margin:0;background:#f2f5fb;color:#192843;font:16px/1.9 "Segoe UI","Malgun Gothic",sans-serif}main{max-width:980px;margin:35px auto;background:white;padding:55px;border:1px solid #dce4ef;border-radius:15px}h1{font-size:34px;line-height:1.4}h2{font-size:24px;margin-top:55px;padding-top:25px;border-top:2px solid #dce4ef;break-after:avoid}h3{font-size:18px;color:#245bdd}p,li{overflow-wrap:anywhere}table{border-collapse:collapse;font-size:13px;width:100%}td,th{border-bottom:1px solid #dce4ef;padding:12px;text-align:left;vertical-align:top}th{background:#edf3ff}.tablewrap{overflow:auto}code{background:#edf3ff;color:#2452a6;font-size:.9em;padding:2px 5px;border-radius:4px}a{color:#245bdd}button{padding:8px 14px;cursor:pointer;background:white;border:1px solid #cad7ec;border-radius:7px;color:#245bdd}details{margin:22px 0;border:1px solid #dce4ef;border-radius:8px;overflow:hidden}summary{padding:12px;background:#edf3ff;cursor:pointer;font-size:13px;font-weight:bold}.filepath{padding:0 14px;font-size:12px}pre{background:#14233a;padding:18px;overflow:auto;font-size:12px;line-height:1.7}pre code{color:#e1ebff;background:transparent;padding:0;white-space:pre}pre .ln{display:inline-block;width:35px;color:#8299bb}pre .explain{color:#9ee2c6;white-space:pre-wrap}.tools{display:flex;gap:18px;align-items:center}@media(max-width:700px){main{padding:24px;margin:10px}}@media print{@page{size:A4;margin:18mm}body{background:white;font-size:11pt}main{margin:0;padding:0;border:none}.tools{display:none}h2{break-before:page}table{font-size:9pt}}</style></head><body><main><div class="tools"><a href="batch-explained.html">← 발표자료</a><button onclick="window.print()">인쇄 / PDF 저장</button></div>${out.join('\n')}</main></body></html>`);
console.log('Created presenter-guide.html');
