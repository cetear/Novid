// 页面逻辑与正式响应字段的离线联调；使用轻量 DOM / HTTP 替身，不证明浏览器视觉或真实服务闭环。
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const root=require('node:path').resolve(__dirname,'../..');
const source=fs.readFileSync(root+'/lab-web/src/main/resources/static/learning.js','utf8');
const html=fs.readFileSync(root+'/lab-web/src/main/resources/static/learning.html','utf8');
class Element {
 constructor(tag='div'){this.tag=tag;this.children=[];this.listeners={};this.dataset={};this.value='';this.disabled=false;this.hidden=false;this.checked=false;this._text='';}
 set textContent(value){this._text=String(value);this.children=[];}get textContent(){return this._text+this.children.map(c=>c.textContent).join('');}
 append(...items){this.children.push(...items);}replaceChildren(...items){this.children=[...items];this._text='';}
 addEventListener(type,callback){(this.listeners[type]??=[]).push(callback);}matches(value){return this.tag===value;}
 querySelector(){return this.children.find(c=>c.tag==='button')||null;}reset(){this.value='';}click(){}
}
function fixture(settings={}){const elements=new Map();for(const match of html.matchAll(/<([a-z][a-z0-9]*)\b[^>]*\bid="([^"]+)"[^>]*>/g)){const node=new Element(match[1]);const attrs=match[0];node.value=attrs.match(/\bvalue="([^"]*)"/)?.[1]||'';node.hidden=/\bhidden\b/.test(attrs);elements.set(match[2],node);}
 const modes=['QUIZ_GENERATION','KNOWLEDGE_COMPILATION'].map((value,i)=>Object.assign(new Element('input'),{value,checked:i===0}));const types=['SINGLE_CHOICE','SHORT_ANSWER'].map(value=>Object.assign(new Element('input'),{value,checked:true}));
 elements.get('difficulty').value='MEDIUM';elements.get('detail').value='DETAILED';
 const document={getElementById:id=>{assert.ok(elements.has(id),'missing HTML id '+id);return elements.get(id);},createElement:tag=>new Element(tag),querySelector:selector=>selector.includes(':checked')?modes.find(m=>m.checked):modes.find(m=>selector.includes(m.value)),querySelectorAll:selector=>selector.includes('name=mode')?modes:types.filter(t=>t.checked)};
 const calls=[];let createdType=settings.taskType||'QUIZ_GENERATION',createdId=10,failCreate=false,savedStatus='SUCCEEDED',taskStatus=settings.status||'SUCCEEDED',stateVersion=7;
 const doc={id:1,knowledgeBaseId:3,title:'资料 <script>文本</script>',documentVersion:1,ingestionStatus:'SUCCEEDED',activeProcessingRevision:1};
 const task=()=>({taskId:createdId,taskType:createdType,status:taskStatus,stateVersion,modelAttempts:8,artifactId:taskStatus==='SUCCEEDED'?20:null});
 const chapters=[{id:'root',parentId:'',title:'父章'},{id:'leaf',parentId:'root',title:'子章'}];
 const saved=()=>({approval:{approvalId:'approval-one',status:'APPLIED',title:'整编文档',content:'# 保存正文\n<script>alert(1)</script>'},document:{...doc,id:9,ingestionStatus:savedStatus}});
 const fetch=async(url,options)=>{calls.push({url,body:options.body?JSON.parse(options.body):undefined,headers:options.headers});let body;
  if(url==='/api/v1/auth/login')body={token:'synthetic-test-token',user:{passwordChangeRequired:false}};
  else if(url.includes('/knowledge-bases'))body=[{id:3,name:'个人库',enabled:true}];
  else if(url.startsWith('/api/v1/documents?'))body=[doc,{...doc,id:2,title:'待处理文档',ingestionStatus:'QUEUED',activeProcessingRevision:null}];
  else if(url==='/api/v1/tasks'&&options.method==='POST'){if(failCreate){failCreate=false;throw new Error('test network interruption');}createdType=JSON.parse(options.body).taskType;body=task();}
  else if(url.includes('/content-plan'))body={plan:{counts:{questions:1,sections:1},quiz:{chapters}},completedUnits:1,completedChapters:2,totalChapters:2,fullSourceRead:true};
  else if(url.includes('/work-packages'))body=[{workPackage:{id:'w1'},status:'ACCEPTED',acceptedParts:1,parts:[{}]}];
  else if(url.endsWith('/retry-plan'))body={completedNodes:12,acceptedPackages:2,remainingExecutionSeconds:-1,remainingTurns:20,remainingAttempts:40,remainingTokens:1000,remainingAmount:10,currency:'CNY',canContinue:true,blockers:[],...settings.retryPlan};
  else if(url.endsWith('/retry')){taskStatus='QUEUED';stateVersion++;body=task();}
  else if(url.includes('/result'))body=createdType==='QUIZ_GENERATION'?{title:'自测',quiz:{questions:[{stem:'问题',answer:'答案',explanation:'条件',options:[]}]},coverageReport:[{id:'root',kind:'QUIZ_ASSOCIATED',available:1,selected:1,reason:'支持关联'}]}:{title:'整编',outline:{chapters},chapters:[{id:'root',sections:[]},{id:'leaf',sections:[{body:'<script>原文示例</script>'}]}],coverageReport:[]};
  else if(url.endsWith('/compilation-save/prepare')||url.includes('/compilation-saves/'))body=saved();
  else if(url.includes('/approvals/'))body=saved().approval;
  else if(url.includes('/origin'))body={originType:'UPLOAD',directSources:[]};
  else if(url.includes('/compilations'))body=[];
  else if(url.includes('/auth/logout'))body=null;
  else if(url.startsWith('/api/v1/tasks/'))body=task();
  else throw new Error('unexpected route '+url);
  return {ok:true,status:body?200:204,headers:{get:()=>null},text:async()=>JSON.stringify(body),json:async()=>body};
 };
 const context=vm.createContext({document,window:{addEventListener(){}},fetch,AbortController,setTimeout:()=>1,clearTimeout(){},crypto:{randomUUID:()=>String(calls.length+Math.random())},URL,console});vm.runInContext(source,context);
 return {elements,calls,context,doc,run:code=>vm.runInContext(code,context),fire:async(id,type='click')=>{for(const callback of elements.get(id).listeners[type]||[])await callback({preventDefault(){}});},setFail:()=>failCreate=true,setSavedStatus:value=>savedStatus=value};
}
test('login binds actual document IDs and ingestion state; plaintext rendering stays inert',async()=>{const f=fixture();await f.fire('login','submit');assert.equal(f.elements.get('workspace').hidden,false);assert.equal(f.elements.get('documents').children[0].children[0].children[0].disabled,false);assert.equal(f.elements.get('documents').children[1].children[0].children[0].disabled,true);assert.match(f.elements.get('documents').textContent,/<script>文本<\/script>/);});
test('quiz selection requires exactly one document and preserves defaults',async()=>{const f=fixture();await f.fire('login','submit');f.run('state.selected.set(1,state.documents[0]);');await f.fire('create-task','submit');const request=f.calls.find(c=>c.url==='/api/v1/tasks');assert.deepEqual(request.body.documentIds,[1]);assert.equal(request.body.quizOptions.questionCount,0);assert.equal(request.headers.Authorization,'Bearer synthetic-test-token');assert.match(f.elements.get('progress').textContent,/2 \/ 2/);assert.match(f.elements.get('packages').textContent,/完成单元 1 \/ 1/);});
test('multiple documents never reach quiz creation and offer compilation guidance',async()=>{const f=fixture();await f.fire('login','submit');f.run('state.selected.set(1,state.documents[0]);state.selected.set(2,state.documents[1]);');await f.fire('create-task','submit');assert.equal(f.calls.filter(c=>c.url==='/api/v1/tasks').length,0);assert.match(f.elements.get('error').textContent,/可先整编/);});
test('save is previewed before explicit confirmation and saved quiz uses only generated body',async()=>{const f=fixture();await f.fire('login','submit');f.run("state.selected.set(1,state.documents[0]);setMode('KNOWLEDGE_COMPILATION');");await f.fire('create-task','submit');f.elements.get('save-base').value='3';await f.fire('prepare-save');assert.equal(f.elements.get('error').hidden,true,f.elements.get('error').textContent);assert.match(f.elements.get('save-body').textContent,/<script>alert/);assert.equal(f.calls.some(c=>c.url.includes('/decision')),false);await f.fire('confirm-save');assert.deepEqual(f.calls.find(c=>c.url.includes('/decision')).body,{approved:true});assert.equal(f.elements.get('quiz-saved').hidden,false);await f.fire('quiz-saved');const requests=f.calls.filter(c=>c.url==='/api/v1/tasks');assert.deepEqual(requests.at(-1).body.documentIds,[9]);assert.equal(requests.at(-1).body.taskType,'QUIZ_GENERATION');});
test('queued saved document cannot start quiz before activation',async()=>{const f=fixture();await f.fire('login','submit');f.run("state.selected.set(1,state.documents[0]);setMode('KNOWLEDGE_COMPILATION');");await f.fire('create-task','submit');f.elements.get('save-base').value='3';await f.fire('prepare-save');f.setSavedStatus('QUEUED');await f.fire('confirm-save');assert.equal(f.elements.get('quiz-saved').hidden,true);const before=f.calls.length;await f.fire('quiz-saved');assert.equal(f.calls.length,before);assert.match(f.elements.get('error').textContent,/尚未入库/);});
test('network retry reuses idempotency key and a completed creation releases it',async()=>{const f=fixture();await f.fire('login','submit');f.run('state.selected.set(1,state.documents[0]);');f.setFail();await f.fire('create-task','submit');await f.fire('create-task','submit');await f.fire('create-task','submit');const calls=f.calls.filter(c=>c.url==='/api/v1/tasks');assert.equal(calls[0].headers['Idempotency-Key'],calls[1].headers['Idempotency-Key']);assert.notEqual(calls[1].headers['Idempotency-Key'],calls[2].headers['Idempotency-Key']);});
test('pagination remains disabled after callbacks and logout clears task state',async()=>{const f=fixture();await f.fire('login','submit');assert.equal(f.elements.get('previous-docs').disabled,true);assert.equal(f.elements.get('next-docs').disabled,true);f.elements.get('origin').textContent='private source';f.elements.get('result').textContent='private result';await f.fire('logout');assert.equal(f.elements.get('origin').textContent,'');assert.equal(f.elements.get('result').textContent,'');assert.equal(f.run('state.token'),'');assert.equal(f.elements.get('workspace').hidden,true);});
for(const taskType of ['QUIZ_GENERATION','KNOWLEDGE_COMPILATION']){
 test(taskType+' failure offers continuation and submits additions on the same task',async()=>{
  const f=fixture({taskType,status:'FAILED'});await f.run('openTask(10)');
  assert.equal(f.elements.get('retry-panel').hidden,false);assert.equal(f.elements.get('retry-task').hidden,false);
  assert.match(f.elements.get('retry-summary').textContent,/已保存 12 个处理结果，已完成 2 个工作包/);
  f.elements.get('retry-minutes').value='60';f.elements.get('retry-turns').value='10';f.elements.get('retry-tokens').value='2000';f.elements.get('retry-amount').value='5';
  await f.fire('retry-task','submit');
  const retry=f.calls.find(c=>c.url==='/api/v1/tasks/10/retry');assert.ok(retry);assert.ok(retry.headers['Idempotency-Key']);
  assert.deepEqual(retry.body,{mode:'CONTINUE',expectedStateVersion:7,additionalExecutionSeconds:3600,additionalTurns:10,additionalAttempts:0,additionalTools:0,additionalRepairs:0,additionalTokens:2000,additionalAmount:5});
  assert.equal(f.calls.some(c=>c.url==='/api/v1/tasks'),false);assert.equal(f.run('state.task.status'),'QUEUED');assert.equal(f.elements.get('retry-panel').hidden,true);
 });
}
test('quiz quality failure displays blockers without offering continuation',async()=>{
 const f=fixture({status:'FAILED',retryPlan:{canContinue:false,blockers:['需要质量重做']}});await f.run('openTask(10)');
 assert.equal(f.elements.get('retry-panel').hidden,false);assert.equal(f.elements.get('retry-task').hidden,true);assert.match(f.elements.get('retry-blockers').textContent,/需要质量重做/);
});
for(const status of ['SUCCEEDED','RUNNING','QUEUED','PAUSED','CANCELLED']){
 test(status+' quiz does not offer failure retry',async()=>{const f=fixture({status});await f.run('openTask(10)');assert.equal(f.elements.get('retry-panel').hidden,true);assert.equal(f.calls.some(c=>c.url.endsWith('/retry-plan')),false);});
}
test('other failed task types do not offer learning retry',async()=>{const f=fixture({status:'FAILED',taskType:'NOTES_PPT'});await f.run('openTask(10)');assert.equal(f.elements.get('retry-panel').hidden,true);assert.equal(f.calls.some(c=>c.url.endsWith('/retry-plan')),false);});

test('explicit quiz count above 512 is sent to the server for capacity planning',async()=>{
 const f=fixture();await f.fire('login','submit');f.run('state.selected.set(1,state.documents[0]);');
 f.elements.get('question-count').value='600';await f.fire('create-task','submit');
 const request=f.calls.find(c=>c.url==='/api/v1/tasks');assert.ok(request);assert.equal(request.body.quizOptions.questionCount,600);
});
