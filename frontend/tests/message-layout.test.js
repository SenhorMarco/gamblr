import test from 'node:test';
import assert from 'node:assert/strict';
import {continuesMessage} from '../static/message-layout.js';
const message=(authorId,sentAt,extra={})=>({authorId,sentAt,...extra});

test('consecutive messages only connect the same author within five minutes',()=>{
  const first=message('a','2026-11-15T10:00:00');
  assert.equal(continuesMessage(first,message('a','2026-11-15T10:05:00')),true);
  assert.equal(continuesMessage(first,message('a','2026-11-15T10:05:01')),false);
  assert.equal(continuesMessage(first,message('b','2026-11-15T10:01:00')),false);
});
test('deleted messages, bet events, midnight and invalid order break the visual run',()=>{
  const first=message('a','2026-11-15T10:00:00'),next=message('a','2026-11-15T10:01:00');
  for(const [before,after] of [[null,next],[{...first,deleted:true},next],[first,{...next,deleted:true}],[{...first,betId:'bet'},next],[first,{...next,betId:'bet'}],[next,first],[first,{...next,sentAt:'invalid'}],[message('a','2026-11-15T23:59:59'),message('a','2026-11-16T00:00:01')]])assert.equal(continuesMessage(before,after),false);
});
