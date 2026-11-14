import test from 'node:test';
import assert from 'node:assert/strict';
import {AuthClient, ClientError} from '../static/auth.js';
const storage = () => {const values=new Map();return {getItem:key=>values.get(key)||null,setItem:(key,value)=>values.set(key,value),removeItem:key=>values.delete(key)};};
const config={supabaseUrl:'https://auth.example',publishableKey:'sb_publishable_test'};
const response=(data,status=200)=>({ok:status>=200&&status<300,status,json:async()=>data});
test('login sends credentials only to Auth and stores a per-tab session',async()=>{const calls=[], store=storage();const auth=new AuthClient(config,{storage:store,fetcher:async(url,options)=>{calls.push({url,options});return response({access_token:'access',refresh_token:'refresh',expires_in:3600,user:{id:'user'}});}});await auth.signIn('user@example.test','password');assert.equal(calls[0].url,'https://auth.example/auth/v1/token?grant_type=password');assert.equal(auth.session.access_token,'access');assert.ok(!store.getItem('gamblr.session').includes('password'));});
test('concurrent refreshes share one request',async()=>{let calls=0;const auth=new AuthClient(config,{storage:storage(),fetcher:async()=>{calls++;await new Promise(resolve=>setTimeout(resolve,5));return response({access_token:'new',refresh_token:'new-refresh',expires_in:3600});}});auth.save({access_token:'old',refresh_token:'old-refresh',expires_at:1});const values=await Promise.all([auth.token(),auth.token(),auth.token()]);assert.deepEqual(values,['new','new','new']);assert.equal(calls,1);});
test('logout during refresh cannot resurrect the session',async()=>{let finish;const auth=new AuthClient(config,{storage:storage(),fetcher:()=>new Promise(resolve=>finish=()=>resolve(response({access_token:'new',refresh_token:'new-refresh'})))});auth.save({access_token:'old',refresh_token:'refresh',expires_at:1});const pending=auth.refresh();auth.clear();finish();await assert.rejects(pending,ClientError);assert.equal(auth.session,null);});
test('invalid refresh clears the session',async()=>{const auth=new AuthClient(config,{storage:storage(),fetcher:async()=>response({error_code:'invalid_grant'},400)});auth.save({access_token:'old',refresh_token:'refresh',expires_at:1});await assert.rejects(auth.refresh(),ClientError);assert.equal(auth.session,null);});
test('separate tabs do not share login state',async()=>{const first=new AuthClient(config,{storage:storage()}),second=new AuthClient(config,{storage:storage()});first.save({access_token:'a',refresh_token:'r'});assert.equal(second.session,null);});

test('signup rejects an obfuscated existing user without saving a session', async () => {
  const store = storage();
  const auth = new AuthClient(config, {storage: store, fetcher: async () => response({id:'hidden', identities:[]})});
  await assert.rejects(auth.signUp('existing@example.test', 'password', 'Nome'), error =>
    error instanceof ClientError && error.code === 'user_already_exists' && error.message.includes('Entrar'));
  assert.equal(auth.session, null);
  assert.equal(store.getItem('gamblr.session'), null);
});
test('signup accepts a new user awaiting confirmation', async () => {
  const user = {id:'new', identities:[{provider:'email'}]};
  const auth = new AuthClient(config, {storage:storage(), fetcher:async () => response(user)});
  assert.deepEqual(await auth.signUp('new@example.test', 'password', 'Nome'), user);
  assert.equal(auth.session, null);
});
test('signup reports an explicit duplicate email error', async () => {
  const auth = new AuthClient(config, {storage:storage(), fetcher:async () => response({error_code:'user_already_exists'}, 422)});
  await assert.rejects(auth.signUp('existing@example.test', 'password', 'Nome'), {code:'user_already_exists'});
});
test('signup saves an immediately authenticated session', async () => {
  const session = {access_token:'access', refresh_token:'refresh', user:{id:'new'}};
  const auth = new AuthClient(config, {storage:storage(), fetcher:async () => response(session)});
  await auth.signUp('new@example.test', 'password', 'Nome');
  assert.equal(auth.session.access_token, 'access');
});
