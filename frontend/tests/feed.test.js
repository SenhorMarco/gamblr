import test from 'node:test';
import assert from 'node:assert/strict';
import {filterFeed} from '../static/feed.js';
const entry=(id,state,day)=>({group:{id},bet:{state,question:'Vai chover?',publishedAt:`2026-11-${day}T12:00:00`,predictionsCloseAt:'2026-11-20T12:00:00',endsAt:'2026-11-21T12:00:00'}});
const entries=[entry('a','OPEN','14'),entry('b','SETTLED','15'),entry('b','RESULT_PUBLISHED','16')];
test('combines group, state, publication dates and question filters',()=>{
 assert.deepEqual(filterFeed(entries,{groups:['b'],status:'active',from:'2026-11-15',to:'2026-11-16',query:'CHOVER'}),[entries[2]]);
 assert.equal(filterFeed(entries,{query:'neve'}).length,0);
 assert.equal(filterFeed(entries,{}).length,3);
});
test('includes entire final local day and excludes following day',()=>{
 assert.deepEqual(filterFeed(entries,{from:'2026-11-15',to:'2026-11-15'}),[entries[1]]);
});
test('distinguishes accepting predictions from awaiting result',()=>{
 assert.deepEqual(filterFeed(entries,{status:'open'},new Date('2026-11-19').getTime()),[entries[0]]);
 assert.equal(filterFeed(entries,{status:'open'},new Date('2026-11-22').getTime()).length,0);
 assert.deepEqual(filterFeed(entries,{status:'awaiting'},new Date('2026-11-22').getTime()),[entries[0]]);
});
