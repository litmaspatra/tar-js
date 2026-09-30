import puppeteer from 'puppeteer-core';
import {execSync} from 'node:child_process';

const chrome = process.env.CHROME_BIN || (() => {
  for (const cmd of ['google-chrome','google-chrome-stable','chromium','chromium-browser']) {
    try { return execSync('command -v '+cmd).toString().trim(); } catch {}
  }
  throw new Error('No Chrome/Chromium found');
})();

const browser = await puppeteer.launch({headless:true,executablePath:chrome,args:['--no-sandbox','--disable-dev-shm-usage']});
const widths=[320,360,412];

const archives=[{id:1,name:'Private Archive',sourceKind:'rclone',messageCount:6,chatCount:1}];
const chats=[{id:10,name:'Alice Test',type:'personal_chat',avatar:null,messageCount:6,lastDate:1788257100,preview:'important document'}];
const messages=[
 {dbId:1,archiveId:1,chatId:10,telegramId:1,dateUnix:1788256800,sender:'Alice Test',senderId:'user1001',text:'Hello from Alice with enough text to test wrapping without collisions.',mediaType:null,mediaPath:null},
 {dbId:2,archiveId:1,chatId:10,telegramId:2,dateUnix:1788256860,sender:'Me',senderId:'user999',text:'My outgoing reply should be aligned on the right.',mediaType:null,mediaPath:null},
 {dbId:3,archiveId:1,chatId:10,telegramId:3,dateUnix:1788256920,sender:'Alice Test',senderId:'user1001',text:'',mediaType:'sticker',mediaPath:'stickers/sticker_1.webp',fileName:'sticker_1.webp'},
 {dbId:4,archiveId:1,chatId:10,telegramId:4,dateUnix:1788256980,sender:'Me',senderId:'user999',text:'',mediaType:'video',mediaPath:'video_files/short.webm',fileName:'short.webm',rawSummary:'duration=4'},
 {dbId:5,archiveId:1,chatId:10,telegramId:5,dateUnix:1788257040,sender:'Alice Test',senderId:'user1001',text:'A long caption that should wrap cleanly and never overlap the timestamp or escape the message bubble on narrow screens.',mediaType:'photo',mediaPath:'photos/photo_1.jpg'},
 {dbId:6,archiveId:1,chatId:10,telegramId:6,dateUnix:1788257100,sender:'Me',senderId:'user999',text:'important document',mediaType:'file',mediaPath:'files/document_1.pdf',fileName:'a-very-long-document-file-name-that-needs-ellipsis.pdf',mimeType:'application/pdf'}
];

async function setup(page,hasArchives=true,pin=false){
  await page.evaluateOnNewDocument((archives,chats,messages,hasArchives,pin)=> {
    window.__browseCalls=[];
    window.TARJS={
      getAppPrefs:()=>JSON.stringify({pinEnabled:pin,displayName:'Me',senderId:'user999',hasProfilePhoto:false}),
      getOwner:()=>JSON.stringify({id:'user999',name:'Me'}),
      listArchives:()=>JSON.stringify(hasArchives?archives:[]),
      listChats:()=>JSON.stringify(chats),
      getMessages:()=>JSON.stringify(messages),
      getMessageWindow:()=>JSON.stringify(messages),
      search:()=>JSON.stringify([{chatId:10,messageDbId:6,chatName:'Alice Test',sender:'Me',text:'important document',dateUnix:1788257100}]),
      verifyPin:p=>p==='1234',setPin:()=>true,disablePin:()=>true,saveIdentity:()=>{},chooseProfilePhoto:()=>{},
      chooseFolder:()=>{},chooseRcloneConfig:()=>{},browseRclone:(remote,path)=>{window.__browseCalls.push({remote,path})},importRcloneFolder:()=>{},unlockRclone:()=>{},rescan:()=>{},makeOffline:()=>{}
    };
  },archives,chats,messages,hasArchives,pin);
}

async function assertLayout(page,label){
  const res=await page.evaluate(()=> {
    const vw=innerWidth;
    const offenders=[];
    document.querySelectorAll('.topbar,.row,.bubble,.card,.source-card,.dialog,.search-field,.action,.file-card,.sticker-wrap,.tgs-sticker').forEach(el=>{
      const r=el.getBoundingClientRect();
      if(r.width>0 && (r.left < -1 || r.right > vw+1)) offenders.push({cls:el.className,left:r.left,right:r.right,vw});
    });
    const overflow=document.documentElement.scrollWidth>vw+1 || document.body.scrollWidth>vw+1;
    const tiny=[...document.querySelectorAll('button')].filter(b=>{const r=b.getBoundingClientRect();return r.width>0&&r.height>0&&(r.width<48||r.height<48)}).map(b=>({text:b.textContent.trim().slice(0,30),w:b.getBoundingClientRect().width,h:b.getBoundingClientRect().height,cls:b.className}));
    return {overflow,offenders,tiny};
  });
  if(res.overflow||res.offenders.length) throw new Error(label+' layout overflow '+JSON.stringify(res));
  const badTiny=res.tiny.filter(x=>!x.cls.includes('button-row'));
  if(badTiny.length) throw new Error(label+' touch target <48 '+JSON.stringify(badTiny));
}

for(const width of widths){
  {
    const p=await browser.newPage();await p.setViewport({width,height:700,deviceScaleFactor:1});await setup(p,false,false);await p.goto('http://127.0.0.1:8765/index.html',{waitUntil:'networkidle0'});await assertLayout(p,'onboarding '+width);await p.close();
  }
  const p=await browser.newPage();await p.setViewport({width,height:760,deviceScaleFactor:1});await setup(p,true,false);await p.goto('http://127.0.0.1:8765/index.html',{waitUntil:'networkidle0'});
  await assertLayout(p,'chats '+width);
  await p.evaluate(()=>openChatById(10));await new Promise(r=>setTimeout(r,80));await assertLayout(p,'chat '+width);
  const sides=await p.evaluate(()=>({incoming:document.querySelectorAll('.message.in').length,outgoing:document.querySelectorAll('.message.out').length}));
  if(!sides.incoming||!sides.outgoing) throw new Error('message alignment missing '+JSON.stringify(sides));
  await p.evaluate(()=>{S.view='search';render()});await assertLayout(p,'search '+width);
  await p.evaluate(()=>{S.view='settings';render()});await assertLayout(p,'settings '+width);
  const remoteCheck=await p.evaluate(()=> {
    rcloneStart({remotes:['b2remote','b2crypt'],engineAvailable:true,hasCrypt:true});
    const txt=document.querySelector('#main').textContent||'';
    const rows=[...document.querySelectorAll('.row')];
    if(!txt.includes('b2remote')||!txt.includes('b2crypt')) return {ok:false,reason:'remote names missing',txt};
    rows[0]?.click();
    return {ok:true,call:window.__browseCalls[0]||null};
  });
  if(!remoteCheck.ok||!remoteCheck.call||remoteCheck.call.remote!=='b2remote'||remoteCheck.call.path!=='') throw new Error('rclone remote UI regression '+JSON.stringify(remoteCheck));
  await assertLayout(p,'rclone remotes '+width);
  await p.evaluate(()=>renderRcloneListing({remote:'b2crypt',path:'Telegram Export',entries:[{Name:'photos',Path:'photos',IsDir:true},{Name:'stickers',Path:'stickers',IsDir:true},{Name:'result.json',Path:'result.json',IsDir:false,Size:12345}]}));await assertLayout(p,'rclone '+width);
  await p.evaluate(()=>editIdentity());await assertLayout(p,'identity dialog '+width);await p.evaluate(()=>closeDialog());
  await p.close();

  const lock=await browser.newPage();await lock.setViewport({width,height:700,deviceScaleFactor:1});await setup(lock,true,true);await lock.goto('http://127.0.0.1:8765/index.html',{waitUntil:'networkidle0'});await assertLayout(lock,'lock '+width);await lock.close();
}
console.log('UI_LAYOUT_OK widths='+widths.join(',')+' screens=onboarding,chats,chat,search,settings,rclone,identity-dialog,lock');
await browser.close();
