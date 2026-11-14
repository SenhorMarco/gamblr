import {AuthClient, ClientError} from './auth.js';
import {createChats} from './chat.js';
import {createResolutions} from './resolution.js';
const $ = id => document.getElementById(id);
const state = {profile:null, groups:[], group:null, bets:[], selected:null, ranking:[], mode:'explore', serial:0, refreshing:false, detailKey:'', listKey:'', signup:false};
state.search='';state.publicGroups=[];
let auth, toastTimer;
const chats=createChats({api,profile:()=>state.profile,onPrivate:friend=>privateConversation(friend)});
const resolutions=createResolutions({api,onChanged:async(data,fromAction)=>{
  if(state.mode!=='group')return;
  const card=state.bets.find(item=>item.bet.id===state.selected);if(!card)return;
  const changed=card.bet.state!==data.state;
  if(changed){card.bet.state=data.state;if(['SETTLED','CANCELLED'].includes(data.state))$('bet-filter').value='all';state.detailKey='';state.listKey='';renderBets();renderDetail();}
  if(changed||fromAction)await refreshGroup(true);
}});
const matchesSearch = group => group.name.toLocaleLowerCase('pt-BR').includes(state.search.toLocaleLowerCase('pt-BR'));
function el(tag, cls, text) {const node=document.createElement(tag);if(cls)node.className=cls;if(text!==undefined)node.textContent=text;return node;}
function show(node, value=true) {node.hidden=!value;}
const points = value => value == null ? '—' : Number(value).toLocaleString('pt-BR',{maximumFractionDigits:6});
const date = value => new Date(value).toLocaleString('pt-BR',{day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'});
const open = bet => bet.state === 'OPEN' && new Date(bet.predictionsCloseAt).getTime() > Date.now();
const active = bet => ['OPEN','RESULT_PUBLISHED'].includes(bet.state);
const betStatus = bet => bet.state==='SETTLED'?'Concluída':bet.state==='CANCELLED'?'Cancelada':bet.state==='RESULT_PUBLISHED'?'Resultado publicado':open(bet)?'Palpites abertos':new Date(bet.endsAt).getTime()>Date.now()?'Palpites fechados':'Aguardando resultado';
function toast(message,error=false) {clearTimeout(toastTimer);$('toast').textContent=message;$('toast').className=error?'error':'';show($('toast'));toastTimer=setTimeout(()=>show($('toast'),false),5500);}
function errorIn(form,error) {const target=form.querySelector('.form-error');target.textContent=error.message || 'Não foi possível concluir a operação.';show(target);}
async function submit(form, action) {const button=form.querySelector('button[type=submit]');button.disabled=true;show(form.querySelector('.form-error'),false);try{await action();}catch(error){errorIn(form,error);}finally{button.disabled=false;}}
async function api(service,path,{method='GET',body}={},retried=false) {
  const token=await auth.token(); let response;
  try {response=await fetch(`/${service}${path}`,{method,headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)})});}
  catch {throw new ClientError('Conexão interrompida. Confira o estado antes de repetir uma operação.');}
  if(response.status===401 && !retried) {await auth.refresh();return api(service,path,{method,body},true);}
  const data=await response.json().catch(()=>({}));
  if(!response.ok) {if(response.status===401)auth.clear();throw new ClientError(data.message || 'Não foi possível concluir a operação.',response.status,data.code);}
  return data;
}
function clearWorkspace() {state.serial++;state.group=null;state.groups=[];state.bets=[];state.selected=null;state.profile=null;state.detailKey='';state.listKey='';state.refreshing=false;$('group-list').replaceChildren();$('bets-list').replaceChildren();$('bet-detail').replaceChildren();$('ranking-list').replaceChildren();}
function signedOut() {resolutions.reset();chats.reset();clearWorkspace();state.search='';state.publicGroups=[];$('group-search').value='';$('auth-form').reset();authMode(false);show($('app-view'),false);show($('auth-view'));document.querySelectorAll('dialog[open]').forEach(d=>d.close());}
async function enter() {
  const user=auth.session.user;
  state.profile=await api('writer','/api/me',{method:'POST',body:{displayName:user?.user_metadata?.display_name || user?.email?.split('@')[0] || 'Integrante'}});
  $('user-name').textContent=state.profile.displayName;$('user-email').textContent=user?.email || '';$('user-initial').textContent=state.profile.displayName.slice(0,1).toUpperCase();
  $('auth-form').reset();show($('auth-view'),false);show($('app-view'));
  chats.start();await loadGroups(); checkStatus();
}
async function loadGroups(preferred) {
  const groups=await api('writer','/api/groups'); if(!state.profile)return;state.groups=groups;renderGroups();
  const next=groups.find(g=>g.id===(preferred || state.group?.id || sessionStorage.getItem('gamblr.group')));
  if(next)await selectGroup(next);else if(groups.length)await selectGroup(groups[0]);else await explore();
}
function renderGroups() {
  const fragment=document.createDocumentFragment();
  for(const group of state.groups.filter(matchesSearch)){const button=el('button','group-nav'+(state.group?.id===group.id?' active':''));button.type='button';button.append(el('span','group-icon',group.name.slice(0,1).toUpperCase()),el('span','',group.name));button.onclick=()=>selectGroup(group).catch(error=>toast(error.message,true));fragment.append(button);}
  $('group-list').replaceChildren(fragment);show($('no-groups'),!state.groups.length);
}
async function explore() {
  resolutions.reset();
  chats.pause();
  state.mode='explore';state.group=null;state.serial++;state.refreshing=false;state.selected=null;state.detailKey='';state.listKey='';renderGroups();
  $('explore-button').classList.add('active');$('page-kicker').textContent='GRUPOS PÚBLICOS';$('page-title').textContent='Encontre sua turma.';$('page-subtitle').textContent='Entre em um grupo e coloque seus palpites em jogo.';
  $('private-nav').classList.remove('active');show($('private-view'),false);show($('betting-column'),false);show($('empty-betting'));$('empty-betting-text').textContent='Selecione um grupo para acompanhar as apostas ativas.';
  show($('group-view'),false);show($('explore-view'));show($('new-group-button'));show($('new-bet-button'),false);
  await loadPublic();
}
async function loadPublic() {
  const groups=await api('writer','/api/groups/public');if(state.mode!=='explore')return;state.publicGroups=groups;renderPublic();
}
function renderPublic() {
  const groups=state.publicGroups.filter(matchesSearch);
  const fragment=document.createDocumentFragment();
  for(const group of groups){const card=el('article','public-card');card.append(el('div','group-icon',group.name.slice(0,1).toUpperCase()),el('h3','',group.name),el('p','',`${group.memberCount} integrante${group.memberCount===1?'':'s'} · entrada livre`));
    const button=el('button','button '+(group.member?'secondary':'primary'),group.member?'Abrir grupo ↗':'Entrar no grupo ↗');button.type='button';button.onclick=async()=>{button.disabled=true;try{if(!group.member)await api('writer',`/api/groups/${group.id}/members/me`,{method:'POST'});await loadGroups(group.id);if(!group.member)toast('Você entrou no grupo. Faça seu primeiro palpite!');}catch(error){toast(error.message,true);}finally{button.disabled=false;}};card.append(button);fragment.append(card);}
  if(!groups.length){const empty=el('div','empty-state');empty.append(el('strong','','O primeiro grupo pode ser o seu.'),el('span','','Crie um grupo e convide outra pessoa para testar com você.'));fragment.append(empty);}
  if(!groups.length&&state.search){fragment.replaceChildren();const empty=el('div','empty-state');empty.append(el('strong','','Nenhum grupo encontrado.'),el('span','','Tente pesquisar outro nome.'));fragment.append(empty);}
  $('public-groups').replaceChildren(fragment);
}
async function selectGroup(group) {
  resolutions.reset();
  chats.openGroup(group);
  const changed=state.group?.id!==group.id;state.mode='group';state.group=group;state.serial++;
  if(changed){state.bets=[];state.selected=null;state.detailKey='';state.listKey='';$('bets-list').replaceChildren(el('p','muted','Carregando apostas…'));show($('bet-detail'),false);$('ranking-list').replaceChildren();$('available-points').textContent='—';$('reserved-points').textContent='—';}
  sessionStorage.setItem('gamblr.group',group.id);renderGroups();$('explore-button').classList.remove('active');$('page-kicker').textContent='SEU GRUPO · PONTOS VIRTUAIS';$('page-title').textContent=group.name;groupSubtitle(group.memberCount);
  $('private-nav').classList.remove('active');show($('private-view'),false);show($('betting-column'));show($('empty-betting'),false);
  show($('explore-view'),false);show($('group-view'));show($('new-group-button'),false);show($('new-bet-button'));
  await refreshGroup(true);
}
async function refreshGroup(force=false) {
  if(!state.group || (state.refreshing&&!force))return;state.refreshing=true;
  const id=state.group.id, serial=++state.serial;
  try{
    const [wallet,bets,ranking]=await Promise.allSettled([api('writer',`/api/groups/${id}/wallet/me`),api('writer',`/api/groups/${id}/bets`),api('reader',`/api/groups/${id}/ranking`)]);
    if(serial!==state.serial || state.group?.id!==id)return;
    if([wallet,bets].some(result=>result.status==='rejected'&&result.reason.status===403)){toast('Você não participa mais desse grupo.',true);await loadGroups();return;}
    if(wallet.status==='fulfilled'){$('available-points').textContent=points(wallet.value.availablePoints);$('reserved-points').textContent=points(wallet.value.reservedPoints);state.wallet=wallet.value;}
    if(bets.status==='fulfilled'){state.bets=bets.value;if(!state.bets.some(card=>card.bet.id===state.selected))state.selected=state.bets[0]?.bet.id || null;renderBets();renderDetail();}
    else if(!state.bets.length)$('bets-list').replaceChildren(el('p','form-error',bets.reason.message));
    if(ranking.status==='fulfilled'){state.ranking=ranking.value.ranking;renderRanking();show($('ranking-error'),false);}else{$('ranking-error').textContent='Ranking indisponível. Confira se o serviço de consultas está em execução.';show($('ranking-error'));}
    await refreshOdds();resolutions.refresh();
  }finally{if(serial===state.serial)state.refreshing=false;}
}
function privateConversation(friend=null) {
  resolutions.reset();
  state.mode='private';state.group=null;state.serial++;state.refreshing=false;state.selected=null;state.bets=[];state.detailKey='';state.listKey='';renderGroups();
  $('explore-button').classList.remove('active');$('private-nav').classList.add('active');$('page-kicker').textContent='MENSAGENS';$('page-title').textContent='Conversas privadas';$('page-subtitle').textContent='Um espaço para conversar com seus amigos.';
  for(const id of ['explore-view','group-view','betting-column','new-group-button','new-bet-button','bet-detail'])show($(id),false);
  for(const id of ['bets-list','bet-detail','ranking-list'])$(id).replaceChildren();
  chats.openPrivate(friend);if(friend)$('page-title').textContent=friend.displayName;
  show($('private-view'));show($('empty-betting'));$('empty-betting-text').textContent='As apostas ficam disponíveis somente nas conversas de grupo.';
}
function renderBets() {
  $('open-bets').textContent=state.bets.filter(card=>open(card.bet)).length;$('bet-count').textContent=state.bets.length;
  const filter=$('bet-filter').value;const cards=state.bets.filter(card=>filter==='open'?active(card.bet):filter==='mine'?!!card.myPrediction:true);
  $('bet-count').textContent=cards.length;
  if(!cards.some(card=>card.bet.id===state.selected)){state.selected=cards[0]?.bet.id||null;state.detailKey='';}
  const key=JSON.stringify([cards,state.selected,cards.map(card=>betStatus(card.bet))]);if(key===state.listKey)return;state.listKey=key;
  const fragment=document.createDocumentFragment();
  for(const card of cards){const bet=card.bet;const button=el('button','bet-card'+(bet.id===state.selected?' selected':''));button.type='button';const top=el('div','card-topline');top.append(el('span','tag',bet.type==='BINARY'?'SIM OU NÃO':'MÚLTIPLA ESCOLHA'),el('span','tag '+(open(bet)?'open':'closed'),betStatus(bet)));button.append(top,el('h3','',bet.question));const foot=el('div','card-foot');foot.append(el('span','',`Palpites até ${date(bet.predictionsCloseAt)}`),el('strong','',card.myPrediction?'✓ Você já palpitou':bet.authorId===state.profile.id?'Sua aposta':'Ver aposta ↗'));button.append(foot);button.onclick=()=>{state.selected=bet.id;state.listKey='';renderBets();renderDetail();refreshOdds();};fragment.append(button);}
  if(!cards.length){const empty=el('div','empty-state');empty.append(el('strong','',state.bets.length?'Nenhuma aposta neste filtro.':'O grupo está esperando uma boa pergunta.'),el('span','',state.bets.length?'Experimente visualizar todas as apostas.':'Publique uma aposta para começar a disputa.'));fragment.append(empty);}
  $('bets-list').replaceChildren(fragment);
}
function renderDetail() {
  const card=state.bets.find(card=>card.bet.id===state.selected);show($('bet-detail'),!!card);if(!card){resolutions.reset();return;}
  const bet=card.bet, key=JSON.stringify([bet.id,bet.state,open(bet),card.myPrediction]);if(key===state.detailKey)return;state.detailKey=key;
  const detail=$('bet-detail');detail.replaceChildren();detail.append(el('span','eyebrow','ESCOLHA SEU LADO'),el('h3','',bet.question),el('p','detail-meta',`Mínimo de ${points(bet.minimumPoints)} pontos · Término ${date(bet.endsAt)}`));
  if(bet.verificationCriterion)detail.append(el('p','criterion',`Verificação: ${bet.verificationCriterion}`));
  const form=el('form');form.id='prediction-form';const options=el('div','option-grid');
  for(const option of bet.options){const label=el('label','option');const input=el('input');input.type='radio';input.name='option';input.value=option.id;input.required=true;input.disabled=!!card.myPrediction || bet.authorId===state.profile.id || !open(bet);if(card.myPrediction?.optionId===option.id)input.checked=true;
    label.append(input,el('span','option-name',option.label));const data=el('span','option-data'),odd=el('span','option-odd','—'),votes=el('span','option-votes','carregando');odd.dataset.odd=option.id;votes.dataset.votes=option.id;data.append(odd,votes);label.append(data);options.append(label);}
  form.append(options);
  if(card.myPrediction){const option=bet.options.find(option=>option.id===card.myPrediction.optionId);form.append(el('div','confirmed-box',`✓ Seu palpite: ${option?.label || 'confirmado'} · ${points(card.myPrediction.points)} pontos reservados. A escolha e o valor são definitivos.`));}
  else if(bet.authorId===state.profile.id)form.append(el('p','prediction-locked','Você publicou esta aposta. Acompanhe os palpites do grupo; o autor não pode participar.'));
  else if(!open(bet))form.append(el('p','prediction-locked','O prazo para confirmar palpites já terminou.'));
  else{const entry=el('div','prediction-entry'),label=el('label','','Quanto você quer reservar?'),input=el('input');input.type='number';input.name='points';input.min=String(bet.minimumPoints);input.step='any';input.value=String(bet.minimumPoints);input.required=true;label.append(input);const button=el('button','button primary','Confirmar palpite ↗');button.type='submit';entry.append(label,button);form.append(entry,el('p','prediction-note','Ao confirmar, os pontos ficam reservados. O palpite não pode ser alterado ou retirado.'));}
  const error=el('p','form-error');error.role='alert';error.hidden=true;form.append(error);
  form.onsubmit=event=>{event.preventDefault();submit(form,async()=>{const values=new FormData(form);if(!values.get('option'))throw new ClientError('Escolha uma opção.');const result=await api('writer',`/api/bets/${bet.id}/predictions`,{method:'POST',body:{optionId:values.get('option'),points:values.get('points')}});card.myPrediction=result;state.detailKey='';renderDetail();toast('Palpite confirmado. Seus pontos foram reservados.');await refreshGroup(true);});};
  if(!active(bet)&&card.myPrediction){const confirmed=form.querySelector('.confirmed-box');if(confirmed)confirmed.textContent=`Seu palpite: ${bet.options.find(option=>option.id===card.myPrediction.optionId)?.label||'confirmado'} · ${points(card.myPrediction.points)} pontos. A reserva foi concluída.`;}
  detail.append(form);resolutions.attach(bet,detail);
}
async function refreshOdds() {
  const id=state.selected;if(!id || state.mode!=='group')return;
  try{const data=await api('reader',`/api/bets/${id}/odds`);if(state.selected!==id)return;for(const option of data.options){const odd=document.querySelector(`[data-odd="${option.optionId}"]`),votes=document.querySelector(`[data-votes="${option.optionId}"]`);if(odd)odd.textContent=option.decimalOdds===null?'—':String(option.decimalOdds).replace('.',',');if(votes)votes.textContent=`${option.votes} voto${option.votes===1?'':'s'}`;}}
  catch{if(state.selected===id)document.querySelectorAll('[data-votes]').forEach(node=>node.textContent='odds indisponíveis');}
}
function renderRanking() {
  groupSubtitle(state.ranking.length);
  const fragment=document.createDocumentFragment();for(const row of state.ranking){const item=el('li','rank-row'+(row.userId===state.profile.id?' me':''));item.append(el('span','rank-position',String(row.position).padStart(2,'0')),el('span','avatar',row.displayName.slice(0,1).toUpperCase()),el('span','rank-name',row.displayName+(row.userId===state.profile.id?' (você)':'')),el('strong','rank-points',points(row.availablePoints)));fragment.append(item);}$('ranking-list').replaceChildren(fragment);
}
function groupSubtitle(count) {if(state.group&&state.profile)$('page-subtitle').textContent=`${count} integrante${count===1?'':'s'} · ${state.group.ownerId===state.profile.id?'Você é dono do grupo':'Grupo compartilhado'}`;}
async function checkStatus() {try{const status=await fetch('/status').then(r=>r.json());const up=status.writer&&status.reader;$('connection-label').classList.toggle('online',up);$('connection-label').querySelector('span').textContent=up?'Tudo conectado':'Conexão parcial';$('service-notice').textContent=!status.writer?'O serviço de apostas está indisponível. Inicie o Spring Boot para continuar.':!status.reader?'O serviço de ranking e odds está indisponível. Inicie o Flask para acompanhar o placar.':'';show($('service-notice'),!up);}catch{}}
function authMode(signup) {state.signup=signup;show($('name-label'),signup);$('auth-name').required=signup;$('auth-password').autocomplete=signup?'new-password':'current-password';$('login-tab').classList.toggle('active',!signup);$('signup-tab').classList.toggle('active',signup);$('auth-title').textContent=signup?'Seu grupo te espera.':'Entre na conversa.';$('auth-description').textContent=signup?'Crie sua conta e faça parte da disputa.':'Acesse sua conta para acompanhar seus grupos.';$('auth-submit').textContent=signup?'Criar minha conta ↗':'Entrar no Gamblr ↗';show($('auth-error'),false);show($('auth-info'),false);}
$('login-tab').onclick=()=>authMode(false);$('signup-tab').onclick=()=>authMode(true);
$('auth-form').onsubmit=event=>{event.preventDefault();submit(event.currentTarget,async()=>{const email=$('auth-email').value.trim(),password=$('auth-password').value;if(state.signup){const data=await auth.signUp(email,password,$('auth-name').value.trim());if(!data.access_token){$('auth-password').value='';authMode(false);$('auth-info').textContent='Cadastro solicitado. Confira seu e-mail para confirmar a conta antes de entrar.';show($('auth-info'));return;}}else await auth.signIn(email,password);await enter();});};
$('logout-button').onclick=async()=>{try{await auth.logout();}catch{toast('Você saiu desta sessão. O serviço de autenticação não confirmou o encerramento remoto.',true);}};
$('explore-button').onclick=()=>explore().catch(error=>toast(error.message,true));
$('home-button').onclick=$('explore-button').onclick;
$('messages-button').onclick=()=>privateConversation();$('private-nav').onclick=()=>privateConversation();
$('group-search').oninput=()=>{state.search=$('group-search').value.trim();renderGroups();if(state.mode==='explore')renderPublic();};
$('search-form').onsubmit=event=>{event.preventDefault();explore().catch(error=>toast(error.message,true));};
$('refresh-button').onclick=()=>{checkStatus();chats.refresh();if(state.mode==='group')refreshGroup(true).catch(error=>toast(error.message,true));else if(state.mode==='explore')loadPublic().catch(error=>toast(error.message,true));};
$('bet-filter').onchange=()=>{const filter=$('bet-filter').value,cards=state.bets.filter(card=>filter==='open'?active(card.bet):filter==='mine'?!!card.myPrediction:true);if(!cards.some(card=>card.bet.id===state.selected))state.selected=cards[0]?.bet.id||null;state.listKey='';state.detailKey='';renderBets();renderDetail();refreshOdds();};
for(const id of ['new-group-button','new-group-side'])$(id).onclick=()=>{$('group-form').reset();show($('group-form').querySelector('.form-error'),false);$('group-dialog').showModal();};
document.querySelectorAll('.close-dialog').forEach(button=>button.onclick=()=>button.closest('dialog').close());
$('new-bet-button').onclick=()=>{$('bet-form').reset();show($('options-label'),false);$('options-label').querySelector('textarea').required=false;show($('bet-form').querySelector('.form-error'),false);const end=new Date(Date.now()+3600000);end.setMinutes(end.getMinutes()-end.getTimezoneOffset());$('bet-ends').value=end.toISOString().slice(0,16);$('bet-dialog').showModal();};
$('bet-type').onchange=()=>{const multiple=$('bet-type').value==='MULTIPLE_CHOICE';show($('options-label'),multiple);$('options-label').querySelector('textarea').required=multiple;};
$('group-form').onsubmit=event=>{event.preventDefault();submit(event.currentTarget,async()=>{const data=new FormData(event.currentTarget);const group=await api('writer','/api/groups',{method:'POST',body:{name:data.get('name'),initialPoints:data.get('initialPoints'),dailyPoints:data.get('dailyPoints'),settlementSeconds:Number(data.get('settlementMinutes'))*60,contestSeconds:Number(data.get('contestMinutes'))*60,visibility:'PUBLIC',entryMode:'IMMEDIATE',invitePolicy:'ALL_MEMBERS'}});$('group-dialog').close();await loadGroups(group.id);toast('Grupo criado. Outra pessoa já pode entrar pela aba Explorar.');});};
$('bet-form').onsubmit=event=>{event.preventDefault();submit(event.currentTarget,async()=>{const data=new FormData(event.currentTarget);const ends=new Date(data.get('endsAt'));if(!Number.isFinite(ends.getTime())||ends.getTime()<=Date.now())throw new ClientError('Escolha um término no futuro.');const options=data.get('type')==='MULTIPLE_CHOICE'?String(data.get('options')).split('\n').map(s=>s.trim()).filter(Boolean):null;const bet=await api('writer',`/api/groups/${state.group.id}/bets`,{method:'POST',body:{question:data.get('question'),type:data.get('type'),options,endsAt:ends.toISOString(),minimumPoints:data.get('minimumPoints'),verificationCriterion:data.get('verificationCriterion')||null}});state.selected=bet.id;$('bet-dialog').close();await refreshGroup(true);toast('Aposta publicada. Os dados já estão bloqueados para edição.');});};
async function start() {
  try{const params=new URLSearchParams(location.search);if(params.has('nova')){sessionStorage.removeItem('gamblr.session');sessionStorage.removeItem('gamblr.group');history.replaceState(null,'','/');}const config=await fetch('/config').then(r=>{if(!r.ok)throw new Error();return r.json();});auth=new AuthClient(config);$('auth-submit').disabled=false;auth.onChange=session=>{if(!session)signedOut();};if(auth.session)await enter();}
  catch(error){$('auth-error').textContent=error.message || 'Não foi possível carregar a configuração local.';show($('auth-error'));}
}
setInterval(()=>{if(!auth?.session || !state.profile || document.hidden)return;if(state.mode==='group')refreshGroup().catch(()=>{});else if(state.mode==='explore')loadPublic().catch(()=>{});},5000);
window.addEventListener('focus',()=>{if(auth?.session&&state.group)refreshGroup().catch(()=>{});});
start();
