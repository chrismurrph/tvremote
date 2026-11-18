#!/usr/bin/env node
// Which Reading/video/photo pads setControls shows, per service, and whether
// "Back to page" can be pressed (2026-11-19: greyed out unless a link shows).
// Pulls setControls out of tvremote.html and runs it against a fake DOM.
// Run: node test/set_controls.js
const fs=require('fs'),path=require('path');
const html=fs.readFileSync(path.join(__dirname,'..','tvremote.html'),'utf8');
const src=html.match(/function setControls\(raw\)\{[\s\S]*?\n(?=\S)/)[0];
const els={};
const document={getElementById:id=>els[id]||(els[id]={style:{},disabled:false})};
const setControls=new Function('document',src+';return setControls')(document);
const shown=id=>els[id].style.display!=='none';
const want={            // phpad  vidpad readpad backDisabled
  page:[false,false,true ,true ],
  rl:  [false,false,true ,false],
  rv:  [false,true ,false,true ],
  ph:  [true ,false,false,true ],
  yt:  [false,true ,false,true ],
  nf:  [false,true ,false,true ]};
let tests=0,checks=0,fails=0;
for(const [svc,[ph,vid,read,back]] of Object.entries(want)){
  tests++;setControls(svc);
  for(const [what,got,exp] of [['phpad',shown('phpad'),ph],['vidpad',shown('vidpad'),vid],
                               ['readpad',shown('readpad'),read],['pgback disabled',els.pgback.disabled,back]]){
    checks++;if(got!==exp){fails++;console.log('FAIL',svc,what,'got',got,'want',exp);}}}
console.log(tests+' tests, '+checks+' checks, '+fails+' failing');
process.exit(fails?1:0);
