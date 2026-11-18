#!/usr/bin/env node
// The ✥ Shift compass on the Reading panel (2026-11-19): open or closed per
// phone (localStorage), and ◀ ▶ greyed out unless /status says :wide.
// Pulls the Shift functions and the compass markup out of tvremote.html.
// Run: node test/shift.js
const fs=require('fs'),path=require('path');
const html=fs.readFileSync(path.join(__dirname,'..','tvremote.html'),'utf8');
const src=html.match(/var shiftOpen=false;[\s\S]*?function setShiftWide\(s\)\{.*\n/)[0];
let tests=0,checks=0,fails=0;
const check=(what,got,exp)=>{checks++;if(got!==exp){fails++;console.log('FAIL',what,'got',got,'want',exp);}};
function load(stored){
  const els={},store=stored==null?{}:{'tvremote-shift':stored};
  const document={getElementById:id=>els[id]||(els[id]={style:{},disabled:true})};
  const localStorage={getItem:k=>k in store?store[k]:null,setItem:(k,v)=>{store[k]=v}};
  const f=new Function('document','localStorage',src+';showShift();return {toggleShift,setShiftWide}')(document,localStorage);
  return {els,store,...f};}
tests++;{const p=load(null);
  check('closed at first',p.els.shiftpad.style.display,'none');
  p.toggleShift();check('open after a press',p.els.shiftpad.style.display,'');
  check('kept on the phone',p.store['tvremote-shift'],'open');
  p.toggleShift();check('closed after another',p.els.shiftpad.style.display,'none');}
tests++;{const p=load('open');check('open again after a reload',p.els.shiftpad.style.display,'');}
tests++;{const p=load(null);
  p.setShiftWide({wide:true});check('wide: ◀ works',p.els.shiftw.disabled,false);check('wide: ▶ works',p.els.shifte.disabled,false);
  p.setShiftWide({wide:false});check('narrow: ◀ grey',p.els.shiftw.disabled,true);check('narrow: ▶ grey',p.els.shifte.disabled,true);
  p.setShiftWide({});check('not said: ◀ grey',p.els.shiftw.disabled,true);}
tests++;{const pad=html.match(/<div id="shiftpad"[\s\S]*?<\/div>/)[0];
  for(const h of ['n','s','e','w'])check('compass sends '+h,pad.includes("page-scroll?how="+h),true);
  check('▲ ◀ ▶ ▼ in order',/▲[\s\S]*◀[\s\S]*▶[\s\S]*▼/.test(pad),true);}
console.log(tests+' tests, '+checks+' checks, '+fails+' failing');
process.exit(fails?1:0);
