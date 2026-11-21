#!/usr/bin/env node
// ▶ Play this page's video on the Reading panel (2026-11-21): shown only when
// /status says :own-video, and it plays the page's video like the 🎬 tile.
// Run: node test/own_video.js
const fs=require('fs'),path=require('path');
const html=fs.readFileSync(path.join(__dirname,'..','tvremote.html'),'utf8');
const src=html.match(/function setOwnVideo\(s\)\{.*\n/)[0];
let tests=0,checks=0,fails=0;
const check=(what,got,exp)=>{checks++;if(got!==exp){fails++;console.log('FAIL',what,'got',got,'want',exp);}};
const els={};const document={getElementById:id=>els[id]||(els[id]={style:{}})};
const setOwnVideo=new Function('document',src+';return setOwnVideo')(document);
tests++;setOwnVideo({'own-video':true});check('shown with its own video',els.pvplay.style.display,'');
setOwnVideo({'own-video':false});check('hidden without',els.pvplay.style.display,'none');
setOwnVideo({});check('hidden when not said',els.pvplay.style.display,'none');
tests++;{const b=html.match(/<button id="pvplay"[^>]*>[^<]*<\/button>/)[0];
  check('plays the page video',b.includes("playVid(this,'here')"),true);
  check('top of the Reading panel',html.indexOf('id="pvplay"')<html.indexOf("page-scroll?how=up"),true);
  check('called from poll',/setOwnVideo\(s\)/.test(html.match(/function poll\(\)\{[\s\S]*?\n(?=\S)/)[0]),true);}
console.log(tests+' tests, '+checks+' checks, '+fails+' failing');
process.exit(fails?1:0);
