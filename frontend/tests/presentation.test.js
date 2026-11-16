import test from 'node:test';
import assert from 'node:assert/strict';
import {betSituation,participationModel,rankingMessage,createParticipation} from '../static/presentation.js';

test('separates all six situations including both time boundaries',()=>{
  const bet={state:'OPEN',predictionsCloseAt:'2026-11-15T10:00:00Z',endsAt:'2026-11-15T12:00:00Z'};
  for(const [time,key] of [['09:59:59','open'],['10:00:00','closed'],['12:00:00','awaiting']])assert.equal(betSituation(bet,Date.parse(`2026-11-15T${time}Z`)).key,key);
  for(const [state,key] of [['RESULT_PUBLISHED','published'],['SETTLED','settled'],['CANCELLED','cancelled']])assert.equal(betSituation({...bet,state},0).key,key);
});
test('participation uses people counts independently of points or odds',()=>{
  const model=participationModel({totalVotes:4,options:[{optionId:'a',label:'Sim',votes:3,points:1,decimalOdds:999},{optionId:'b',label:'Não',votes:1,points:999}]});
  assert.deepEqual(model.options.map(option=>option.share),[.75,.25]);
  assert.equal(participationModel({totalVotes:0,options:[{optionId:'a',label:'Sim',votes:0}]}).options[0].share,0);
});
test('missing and inconsistent participation cannot masquerade as zero votes',()=>{
  for(const data of [null,{}, {totalVotes:0,options:[]},{totalVotes:4,options:[{optionId:'a',label:'Sim',votes:3}]},{totalVotes:0,options:[{optionId:'a',label:'Sim',votes:-1}]}])assert.throws(()=>participationModel(data));
});
test('ranking shows personal position and gap without changing the server ordering',()=>{
  const rows=[{userId:'a',position:3,availablePoints:'100'},{userId:'me',position:4,availablePoints:'85'}];
  assert.equal(rankingMessage(rows,'me'),'Você está em 4º lugar, a 15 pontos do próximo colocado.');
  assert.equal(rows[0].userId,'a');assert.match(rankingMessage([{userId:'me',position:1,availablePoints:'0'}],'me'),/Você está em 1º lugar/);
});
test('ranking handles ties and exact decimal balances beyond Number precision',()=>{
  const rows=[{userId:'a',position:1,availablePoints:'9007199254740993.0001'},{userId:'me',position:2,availablePoints:'9007199254740993.0000'}];
  assert.match(rankingMessage(rows,'me'),/a 0,0001 pontos/);
  rows[1].availablePoints=rows[0].availablePoints;assert.match(rankingMessage(rows,'me'),/empatado em pontos/);
  assert.match(rankingMessage([], 'me'),/não está disponível/);
});
test('participation shares one request per group and discards responses after logout',async()=>{
  let resolve,calls=0,updates=0;
  const loader=createParticipation({api:()=>{calls++;return new Promise(done=>resolve=done);},onUpdate:()=>updates++});
  const first=loader.load('g'),second=loader.load('g');assert.equal(calls,1);
  loader.reset();resolve({groupId:'g',bets:[]});await Promise.all([first,second]);
  assert.equal(updates,0);assert.equal(loader.get('g','a').status,'loading');
});
test('participation rejects cross-group payloads and failed requests',async()=>{
  for(const api of [async()=>({groupId:'other',bets:[]}),async()=>{throw new Error('unavailable');}]){
    const loader=createParticipation({api,onUpdate:()=>{}});await loader.load('g');assert.equal(loader.get('g','a').status,'error');
  }
});
