#!/usr/bin/env node
// What the phone's status line says, and which switch button it lights, for
// each service /status reports (2026-11-21, for a page's own video, "pv").
// Pulls poll() out of tvremote.html and runs it with every other name it uses
// stubbed (a `with` scope that answers a no-op for anything not given here).
// Run: node test/poll_status.js
const fs=require('fs'),path=require('path');
const html=fs.readFileSync(path.join(__dirname,'..','tvremote.html'),'utf8');
const src=html.match(/function poll\(\)\{[\s\S]*?\n(?=\S)/)[0];
const els={};
const el=id=>els[id]||(els[id]={id,style:{},textContent:'',classList:{toggle(){}}});
let lit=null,status=null;
const given={
  document:{getElementById:el,body:{classList:{toggle(){}}}},
  fetch:()=>Promise.resolve({json:()=>status}),
  setTimeout:()=>0,clearTimeout:()=>{},window:{},encodeURIComponent,
  who:'chris',switching:false,svc:'yt',polling:false,
  setSvc:v=>{lit=v},clock:ms=>String(Math.round(ms/1000))+'s',Math,JSON,Promise,
};
const scope=new Proxy(given,{has:()=>true,
  get:(o,k)=>k===Symbol.unscopables?undefined:(k in o?o[k]:()=>{}),
  set:(o,k,v)=>{o[k]=v;return true}});
const poll=new Function('scope','with(scope){'+src+';return poll}')(scope);
async function run(s){status=Object.assign({chrome:true},s);lit=null;given.polling=false;
  for(const k in els)els[k].textContent='';
  poll();await new Promise(r=>setImmediate(r));await new Promise(r=>setImmediate(r));
  return {now:el('now').textContent,lit};}
let tests=0,checks=0,fails=0;
function is(name,got,want){checks++;if(got!==want){fails++;console.log('FAIL',name,'got',JSON.stringify(got),'want',JSON.stringify(want));}}
(async()=>{
  tests++;let r=await run({service:'yt',url:'https://www.youtube.com/watch?v=x',playing:true,title:'T'});
  is('yt now',r.now,'▶ Playing — T');is('yt lit',r.lit,null);
  tests++;r=await run({service:'rv',url:'https://www.youtube.com/watch?v=y',playing:false,title:'V'});
  is('rv now',r.now,'⏸ Paused from Reading — V');is('rv lit',r.lit,'page');
  tests++;r=await run({service:'page',page:{title:'P'}});
  is('page now',r.now,'📖 Reading — P');is('page lit',r.lit,'page');
  {
    tests++;r=await run({service:'pv',url:'https://x.substack.com/p/a',playing:true,title:'Sub post',time:5000,duration:60000});
    is('pv now',r.now,'▶ Playing from Reading — Sub post');is('pv lit',r.lit,'page');}
  {
    tests++;r=await run({service:'nf',url:'https://www.netflix.com/watch/1',playing:false,title:'It','nf-error':'M7500'});
    is('nf error now',r.now,'⚠ Netflix stopped (M7500) — press ⏯ to reload it — It');}
  console.log(tests+' tests, '+checks+' checks, '+fails+' failing');
  process.exit(fails?1:0);})();
