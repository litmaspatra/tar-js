const fs=require('fs'),vm=require('vm');
const store=new Map();
class Elem{constructor(){this.innerHTML='';this.textContent='';this.value='';this.checked=false;this.type='text';this.disabled=false;this.scrollTop=0;this.scrollHeight=1000;this.id='';this.className='';this.classList={add(){},remove(){},toggle(){}};} focus(){} scrollIntoView(){} remove(){} }
const elems=new Map();
function el(sel){if(!elems.has(sel)) elems.set(sel,new Elem()); return elems.get(sel)}
const document={querySelector:el,querySelectorAll(){return[]},getElementById(id){return elems.get('#'+id)||null},createElement(){return new Elem()},head:{appendChild(){}},body:{appendChild(e){if(e&&e.id)elems.set('#'+e.id,e)}}};
let importCall=null,unlockCall=null,avatarCall=null;
const TARJS={
 listArchives:()=>JSON.stringify([{id:1,name:'Dummy Archive',messageCount:6,chatCount:1,sourceKind:'rclone'}]),
 listChats:()=>JSON.stringify([{id:10,name:'Dummy Alice',messageCount:6,lastDate:1790467200,preview:'Great',hasCustomAvatar:false,avatarVersion:0}]),
 getMessages:()=>JSON.stringify([]),getMessageWindow:()=>JSON.stringify([]),search:()=>JSON.stringify([]),
 indexStatus:()=>JSON.stringify({phase:'idle',archiveId:-1,processed:0,total:0,detected:0,detail:''}),
 rclonePasswordRemembered:()=>false,ownerProfile:()=>JSON.stringify({name:'Me',hasAvatar:false,avatarVersion:0}),
 importRcloneFolder:(r,p)=>{importCall=[r,p]}, browseRclone(){}, unlockRclone:(p,r)=>{unlockCall=[p,r]},
 setChatAvatar:id=>{avatarCall=id},setOwnerAvatar(){},setOwnerName(){},forgetRclonePassword(){},rescan(){},makeOffline(){},chooseFolder(){},chooseRcloneConfig(){}
};
const ctx={console,document,window:{},TARJS,localStorage:{getItem:k=>store.has(k)?store.get(k):null,setItem:(k,v)=>store.set(k,String(v))},setTimeout:(f)=>{if(typeof f==='function')f()},clearTimeout(){},setInterval(){return 1},clearInterval(){},requestAnimationFrame:f=>f(),Blob:global.Blob,Response:global.Response,URLSearchParams,Date,JSON,Map,String,Number,Math};
ctx.window=ctx; vm.createContext(ctx);
const code=fs.readFileSync(process.argv[2],'utf8'); vm.runInContext(code,ctx,{filename:'app.js'});
function run(x){return vm.runInContext(x,ctx)}
run(`S.archive={id:1,name:'Dummy Archive',messageCount:6,chatCount:1,sourceKind:'rclone'}; S.chats=[{id:10,name:'Dummy Alice',messageCount:6,lastDate:1790467200,preview:'Great',hasCustomAvatar:false,avatarVersion:0}]; S.chat={id:10,name:'Dummy Alice'}; localStorage.setItem('tarjs.swap.10','0')`);
const msgs=[{dbId:1,telegramId:1,dateUnix:1790467200,sender:'Dummy Alice',mine:false,text:'Hey'},{dbId:2,telegramId:2,dateUnix:1790467201,sender:'Me',mine:true,text:'Hello'}];
ctx.__msgs=msgs;
let html=run(`renderMessages(__msgs)`);
if(!html.includes('message mine" data-id="2"')) throw Error('owner message not right-side by default');
if(html.includes('message mine" data-id="1"')) throw Error('incoming message incorrectly right-side');
run(`S.lastMessages=__msgs; swapSides()`);
if(store.get('tarjs.swap.10')!=='1') throw Error('swap button did not persist state');
html=run(`renderMessages(__msgs)`);
if(!html.includes('message mine" data-id="1"') || html.includes('message mine" data-id="2"')) throw Error('swap did not reverse sides');
if(!el('#main').innerHTML.includes('Swap sides')) throw Error('swap button not rendered in chat');
if(!el('#main').innerHTML.includes('Chat photo')) throw Error('per-chat photo button missing from chat');
avatarCall=null; run(`TARJS.setChatAvatar(S.chat.id)`); if(avatarCall!==10) throw Error('chat photo did not target active conversation');
run(`S.view='settings'; archiveSettings()`);
if(!el('#main').innerHTML.includes('Chat appearance')||!el('#main').innerHTML.includes('Dummy Alice')||!el('#main').innerHTML.includes('Change chat photo')) throw Error('chat-specific appearance settings missing');
ctx.__listing={remote:'cryptdummy',path:'Archive One',entries:[{Name:'media',IsDir:true},{Name:'result.json',IsDir:false},{Name:'readme.txt',IsDir:false}]};
run(`S.importing=false; renderRcloneListing(__listing)`);
if(!el('#main').innerHTML.includes('Telegram archive detected')||!el('#main').innerHTML.includes('result.json')||!el('#main').innerHTML.includes('Use this folder')) throw Error('result.json not visibly detected');
run(`useRcloneFolder()`);
if(!importCall||importCall[0]!=='cryptdummy'||importCall[1]!=='Archive One') throw Error('Use this folder did not invoke native import');
ctx.__empty={remote:'cryptdummy',path:'Empty',entries:[]}; run(`S.importing=false; renderRcloneListing(__empty)`);
if(!el('#main').innerHTML.includes('This folder is empty')) throw Error('empty folder state missing');
run(`renderRclonePassword()`);
if(!el('#main').innerHTML.includes('Remember me')) throw Error('remember me missing');
el('#rcpass').value='secret'; el('#rememberRc').checked=true; TARJS.unlockRclone(el('#rcpass').value,el('#rememberRc').checked);
if(!unlockCall||unlockCall[0]!=='secret'||unlockCall[1]!==true) throw Error('remember-me unlock semantics failed');
const progress=run(`indexStateCard({phase:'indexing',processed:192933,total:1093230,detected:1093230,detail:'x'})`);
if(!progress.includes('192,933')||!progress.includes('1,093,230')||!progress.includes('%')) throw Error('live count progress card missing');
console.log('TARJS_UI_NAV_SWAP_CHAT_PHOTO_REMEMBER_BACKGROUND_PROGRESS_HARNESS_OK');
