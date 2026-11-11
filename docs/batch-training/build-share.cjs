const fs=require('fs');
const path=require('path');
const assert=require('assert/strict');
const dir=__dirname;
let presentation=fs.readFileSync(path.join(dir,'batch-explained.html'),'utf8');
const guide=fs.readFileSync(path.join(dir,'presenter-guide.html'),'utf8');
let guideBody=guide.match(/<main>([\s\S]*?)<\/main>/)[1];
guideBody=guideBody.replace(/<div class="tools">[\s\S]*?<\/div>/,'');
presentation=presentation.replace('<header>','<header id="top">');
presentation=presentation.replace('href="presenter-guide.html"','href="#presenter-guide"');
presentation=presentation.replace('</nav><main>','<a href="#presenter-guide">14 발표자 상세 가이드</a></nav><main>');
presentation=presentation.replace('</main></div><footer>',`<section id="presenter-guide" class="shared-guide"><div class="kicker">14 / PRESENTER GUIDE</div><p><a href="#top">↑ 발표자료 처음으로</a></p>${guideBody}</section></main></div><footer>`);
presentation=presentation.replace('<title>SAI 배치 아키텍처 · 팀 설명자료</title>','<title>SAI 배치 교육자료 · 팀 공유용 통합본</title>');
presentation=presentation.replace('</style>','.shared-guide h2{margin-top:42px;padding-top:18px;border-top:1px solid var(--line)}.shared-guide h1{font-size:30px}.shared-guide li{margin:10px 0}.shared-guide code{overflow-wrap:anywhere}.shared-guide pre code{overflow-wrap:normal}a{color:var(--accent)}header a{color:white}@media print{.shared-guide{break-before:page}.shared-guide h2{break-after:avoid}} </style>');
presentation=presentation.replace('외부 라이브러리·폰트·네트워크 요청 없이 열리는 독립 HTML','발표자료와 상세 가이드를 합친 공유용 단일 HTML · 다운로드 후 브라우저에서 열기');
const ids=[...presentation.matchAll(/\bid="([^"]+)"/g)].map(m=>m[1]);
assert.equal(ids.length,new Set(ids).size,'duplicate IDs');
for(const [,href] of presentation.matchAll(/href="([^"]+)"/g)){
 assert(href.startsWith('#'),`external/local link: ${href}`);
 assert(ids.includes(href.slice(1)),`missing anchor: ${href}`);
}
assert(!/file:\/\/|C:[\\/]|<script[^>]+src=|<link[^>]+href=/i.test(presentation),'local or external dependency');
assert(presentation.includes('16. 주요 기능별 실제 코드와 설명 주석'));
fs.writeFileSync(path.join(dir,'sai-batch-team-share.html'),presentation);
console.log('PASS: standalone combined HTML, internal links, unique IDs, no local paths or external assets');
