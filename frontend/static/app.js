import {filterFeed} from './feed.js';
import {AuthClient, ClientError} from './auth.js';
import {createChats} from './chat.js';
import {createResolutions} from './resolution.js';
import {createGroupTools} from './groups.js';
import {groupName} from './personalization.js';
import {betSituation,statusTag,createParticipation,participationView,rankingMessage,emptyState} from './presentation.js';
import {initializeSidebars} from './sidebars.js';
const $ = id => document.getElementById(id);
const state = {profile:null, groups:[], group:null, bets:[], selected:null, ranking:[], mode:'explore', serial:0, refreshing:false, detailKey:'', listKey:'', signup:false};
state.search='';state.publicGroups=[];
let auth, toastTimer;
const betCache=new Map();
const participation=createParticipation({api,onUpdate:refreshParticipationSlots});
function participationSlot(group,bet){const slot=el('div','participation-slot');slot.dataset.participation=bet.id;slot.dataset.group=group;slot.bet=bet;slot.append(participationView(bet,participation.get(group,bet.id)));return slot;}
function refreshParticipationSlots(group){document.querySelectorAll('.participation-slot').forEach(slot=>{if(slot.dataset.group===group)slot.replaceChildren(participationView(slot.bet,participation.get(group,slot.bet.id)));});}
function failureState(title,description,retry){const panel=emptyState({kind:'search',title,description,actions:[{label:'Tentar novamente',run:retry}]});panel.classList.add('load-failure');return panel;}
function inviteAction(){return {label:'Convide alguém para o grupo',run:()=>groupTools.invite()};}
const cachedBets=id=>{const item=betCache.get(id);return item&&Date.now()-item.time<60000?structuredClone(item.cards):null;};
function rememberBets(id,cards){betCache.delete(id);betCache.set(id,{time:Date.now(),cards:structuredClone(cards)});if(betCache.size>20)betCache.delete(betCache.keys().next().value);}
const groupTools=createGroupTools({api,profile:()=>state.profile,currentGroup:()=>state.mode==='group'?state.group:null,onChange:preferred=>loadGroups(preferred)});
const chats=createChats({api,profile:()=>state.profile,onPrivate:friend=>privateConversation(friend),onBet:id=>{
  if(!state.bets.some(card=>card.bet.id===id)){toast('Esta aposta não está mais disponível.',true);return;}
  $('bet-filter').value='all';state.selected=id;state.detailKey='';state.listKey='';renderBets();renderDetail();refreshOdds();$('bet-detail').scrollIntoView({behavior:'smooth',block:'nearest'});
}});
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
const betStatus = bet => betSituation(bet).label;
function toast(message,error=false) {clearTimeout(toastTimer);$('toast').textContent=message;$('toast').className=error?'error':'';show($('toast'));toastTimer=setTimeout(()=>show($('toast'),false),5500);}
function errorIn(form,error) {const target=form.querySelector('.form-error');target.textContent=error.message || 'Não foi possível concluir a operação.';show(target);}
async function submit(form, action) {const button=form.querySelector('button[type=submit]');button.disabled=true;show(form.querySelector('.form-error'),false);try{await action();}catch(error){errorIn(form,error);}finally{button.disabled=false;}}
async function api(service,path,{method='GET',body,headers={},signal}={},retried=false) {
  const token=await auth.token(); let response;
  try {response=await fetch(`/${service}${path}`,{method,signal,headers:{...headers,Authorization:`Bearer ${token}`,'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)})});}
  catch(error) {if(error.name==='AbortError')throw error;throw new ClientError('Conexão interrompida. Confira o estado antes de repetir uma operação.');}
  if(response.status===401 && !retried) {await auth.refresh();return api(service,path,{method,body,headers,signal},true);}
  const data=await response.json().catch(()=>({}));
  if(!response.ok) {if(response.status===401)auth.clear();throw new ClientError(data.message || 'Não foi possível concluir a operação.',response.status,data.code);}
  if(method!=='GET')betCache.clear();
  return data;
}
function clearWorkspace() {state.serial++;state.group=null;state.groups=[];state.bets=[];state.selected=null;state.profile=null;state.detailKey='';state.listKey='';state.refreshing=false;$('group-list').replaceChildren();$('bets-list').replaceChildren();$('bet-detail').replaceChildren();$('ranking-list').replaceChildren();}
function signedOut() {participation.reset();betCache.clear();feedEntries=[];feedPartial=false;feedError=null;$('feed-list').replaceChildren();$('feed-groups').replaceChildren();$('feed-filters').reset();groupTools.reset();resolutions.reset();chats.reset();clearWorkspace();state.search='';state.publicGroups=[];state.publicError=null;$('group-search').value='';$('auth-form').reset();authMode(false);show($('app-view'),false);show($('auth-view'));document.querySelectorAll('dialog[open]').forEach(d=>d.close());}
async function enter() {
  const user=auth.session.user;
  state.profile=await api('writer','/api/me',{method:'POST',body:{displayName:user?.user_metadata?.display_name || user?.email?.split('@')[0] || 'Integrante'}});
  $('user-name').textContent=state.profile.displayName;$('user-email').textContent=user?.email || '';$('user-initial').textContent=state.profile.displayName.slice(0,1).toUpperCase();
  $('auth-form').reset();show($('auth-view'),false);show($('app-view'));
  groupTools.start();chats.start();await loadGroups(); checkStatus();
}
async function loadGroups(preferred) {
  const groups=await api('writer','/api/groups'); if(!state.profile)return;state.groups=groups;renderGroups();
  const next=groups.find(g=>g.id===(preferred || state.group?.id || sessionStorage.getItem('gamblr.group')));
  if(next)await selectGroup(next);else if(groups.length)await selectGroup(groups[0]);else await explore();
}
function renderGroups() {
  if(state.mode!=='group'&&oddsRequest){oddsRequest.controller.abort();oddsRequest=null;}
  show($('feed-view'),state.mode==='feed');$('feed-button').classList.toggle('active',state.mode==='feed');
  const fragment=document.createDocumentFragment();
  for(const group of state.groups.filter(matchesSearch)){const button=el('button','group-nav'+(state.group?.id===group.id?' active':''));button.type='button';button.append(el('span','group-icon',group.name.slice(0,1).toUpperCase()),el('span','',group.name));button.onclick=()=>selectGroup(group).catch(error=>toast(error.message,true));fragment.append(button);}
  $('group-list').replaceChildren(fragment);show($('no-groups'),!state.groups.length);
}
async function openFeed() {
  resolutions.reset();chats.pause();state.mode='feed';state.group=null;state.selected=null;
  state.serial++;state.refreshing=false;renderGroups();
  $('explore-button').classList.remove('active');$('private-nav').classList.remove('active');
  $('page-kicker').textContent='SEUS GRUPOS';$('page-title').textContent='Feed';
  $('page-subtitle').textContent='Os palpites da sua turma, todos por aqui.';
  for(const id of ['explore-view','group-view','private-view','betting-column','empty-betting','new-bet-button'])show($(id),false);
  show($('new-group-button'));await loadFeed();
}
let feedLoading=false,feedEntries=[],feedPartial=false,feedError=null;
function renderFeed(){
  if(feedError){$('feed-list').replaceChildren(failureState('Não foi possível carregar o Feed.',feedError,()=>loadFeed()));$('feed-status').textContent='Feed indisponível.';return;}
  const from=$('feed-from').value,to=$('feed-to').value;
  const invalid=from&&to&&from>to;show($('feed-filter-error'),!!invalid);$('feed-filter-error').textContent=invalid?'A data inicial deve ser anterior ou igual à final.':'';
  const entries=invalid?[]:filterFeed(feedEntries,{status:$('feed-state').value,groups:Array.from($('feed-groups').querySelectorAll('input:checked')).map(input=>input.value),from,to,query:$('feed-query').value});

    const fragment=document.createDocumentFragment();
    for(const {group,bet} of entries){
      const card=el('article',`public-card feed-bet-card situation-${betSituation(bet).key}`);card.append(el('p','muted',group.name),statusTag(bet),el('h3','bet-question',bet.question),el('p','muted small',date(bet.publishedAt)),participationSlot(group.id,bet));
      const button=el('button','button secondary','Ver aposta ↗');button.type='button';
      button.onclick=async()=>{button.disabled=true;try{await selectGroup(group);if(state.mode!=='group'||state.group?.id!==group.id)return;if(!state.bets.some(item=>item.bet.id===bet.id))throw new ClientError('Esta aposta não está mais disponível.');state.selected=bet.id;state.detailKey='';state.listKey='';$('bet-filter').value='all';renderBets();renderDetail();refreshOdds();$('bet-detail').scrollIntoView({behavior:'smooth',block:'nearest'});}catch(error){toast(error.message,true);}finally{button.disabled=false;}};
      card.append(button);fragment.append(card);
    }
    if(!entries.length){
      const filtered=invalid||$('feed-state').value!=='all'||$('feed-query').value.trim()||from||to||$('feed-groups').querySelector('input:checked');
      if(feedPartial)fragment.append(failureState('Não foi possível carregar todas as apostas.','Alguns grupos estão indisponíveis. Tente atualizar o Feed.',()=>loadFeed()));
      else if(filtered)fragment.append(emptyState({kind:'search',title:'Nenhuma aposta corresponde aos filtros.',description:invalid?'Ajuste o intervalo de datas para continuar.':'Experimente outros critérios ou veja todas as apostas.',actions:[{label:'Limpar filtros',run:()=>$('feed-filters').reset()}]}));
      else fragment.append(emptyState({kind:'bets',title:state.groups.length?'Seu Feed está esperando a primeira aposta.':'Seu Feed começa com um grupo.',description:'Os próximos palpites das suas turmas vão aparecer aqui.',actions:[state.groups.length?{label:'Publique a primeira aposta',run:async()=>{await selectGroup(state.groups[0]);$('new-bet-button').click();}}:{label:'Criar grupo',run:()=>$('new-group-button').click()}]}));
    }
    $('feed-list').replaceChildren(fragment);
    $('feed-status').textContent=feedPartial&&!entries.length?'Não foi possível verificar as apostas de todos os grupos.':(feedPartial?'Alguns grupos não puderam ser carregados. ':'')+(invalid?'':entries.length?`${entries.length} aposta(s) encontrada(s).`:feedEntries.length?'Nenhuma aposta corresponde aos filtros.':'Nenhuma aposta disponível nos seus grupos.');
}
async function loadFeed({background=false}={}) {
  if(feedLoading)return;feedLoading=true;const serial=state.serial;
  if(!background)$('feed-status').textContent='Carregando apostas…';
  try {
    const groups=await api('writer','/api/groups');
    if(state.mode!=='feed'||serial!==state.serial)return;
    const cached=groups.flatMap(group=>(cachedBets(group.id)||[]).map(card=>({group,bet:card.bet})));
    if(cached.length&&!feedEntries.length){feedEntries=cached.sort((a,b)=>new Date(b.bet.publishedAt)-new Date(a.bet.publishedAt));renderFeed();$('feed-status').textContent='Dados em cache — atualizando…';}
    const results=await Promise.allSettled(groups.map(async group=>{const cards=await api('writer',`/api/groups/${group.id}/bets`);if(serial===state.serial)rememberBets(group.id,cards);return {group,cards};}));
    if(state.mode!=='feed'||serial!==state.serial)return;
    state.groups=groups;renderGroups();
    const entries=results.filter(result=>result.status==='fulfilled').flatMap(result=>result.value.cards.map(card=>({group:result.value.group,bet:card.bet})));
    entries.sort((a,b)=>new Date(b.bet.publishedAt)-new Date(a.bet.publishedAt)||a.bet.id.localeCompare(b.bet.id));
    feedEntries=entries;feedPartial=results.some(result=>result.status==='rejected');feedError=null;
    const selected=new Set(Array.from($('feed-groups').querySelectorAll('input:checked')).map(input=>input.value));
    $('feed-groups').replaceChildren(...groups.map(group=>{const label=el('label','feed-group-choice'),input=el('input');input.type='checkbox';input.value=group.id;input.checked=selected.has(group.id);label.append(input,document.createTextNode(group.name));return label;}));
    renderFeed();await Promise.allSettled(groups.map(group=>participation.load(group.id)));
  }catch(error){if(state.mode==='feed'&&serial===state.serial){feedError=error.message;renderFeed();}}
  finally{feedLoading=false;}
}
async function explore() {
  resolutions.reset();
  chats.pause();
  state.mode='explore';state.group=null;state.serial++;state.refreshing=false;state.selected=null;state.detailKey='';state.listKey='';renderGroups();
  $('explore-button').classList.add('active');$('page-kicker').textContent='GRUPOS PÚBLICOS';$('page-title').textContent='Encontre sua turma.';$('page-subtitle').textContent='Encontre sua turma e entre na conversa.';
  $('private-nav').classList.remove('active');show($('private-view'),false);show($('betting-column'),false);show($('empty-betting'));$('empty-betting-text').textContent='Selecione um grupo para acompanhar as apostas ativas.';
  show($('group-view'),false);show($('explore-view'));show($('new-group-button'));show($('new-bet-button'),false);
  await loadPublic();
}
async function loadPublic() {
  const session=state.serial;
  if(!state.publicGroups.length)$('public-groups').replaceChildren(el('p','muted','Carregando grupos…'));
  try{const groups=await api('writer','/api/groups/public');if(state.mode!=='explore'||session!==state.serial)return;state.publicGroups=groups;state.publicError=null;renderPublic();}
  catch(error){if(state.mode==='explore'&&session===state.serial){state.publicError=error.message;renderPublic();}}
}
function renderPublic() {
  const groups=state.publicGroups.filter(matchesSearch);
  const fragment=document.createDocumentFragment();
  for(const group of groups){const mode=group.entryMode||'IMMEDIATE',description=mode==='REQUEST'?'entrada por solicitação':mode==='INVITE'?'entrada por convite':'entrada livre';const card=el('article','public-card');card.append(el('div','group-icon',group.name.slice(0,1).toUpperCase()),el('h3','',group.name),el('p','',`${group.memberCount} integrante${group.memberCount===1?'':'s'} · ${description}`));
    const label=group.member?'Abrir grupo ↗':group.requestPending?'Solicitação enviada':mode==='REQUEST'?'Solicitar entrada':mode==='INVITE'?'Somente por convite':'Entrar no grupo ↗';const button=el('button','button '+(group.member?'secondary':'primary'),label);button.type='button';button.disabled=!group.member&&(!!group.requestPending||mode==='INVITE');button.onclick=async()=>{button.disabled=true;try{if(!group.member&&mode==='REQUEST'){await api('writer',`/api/groups/${group.id}/join-requests`,{method:'POST'});await loadPublic();toast('Solicitação enviada ao dono e aos moderadores.');return;}if(!group.member)await api('writer',`/api/groups/${group.id}/members/me`,{method:'POST'});await loadGroups(group.id);if(!group.member)toast('Você entrou no grupo. Faça seu primeiro palpite!');}catch(error){toast(error.message,true);}finally{button.disabled=false;}};card.append(button);fragment.append(card);}
  if(state.publicError)fragment.append(failureState('Não foi possível carregar os grupos.',state.publicError,()=>loadPublic()));
  else if(!groups.length)fragment.append(emptyState({kind:state.search?'search':'groups',title:state.search?'Nenhum grupo encontrado.':'O primeiro grupo pode ser o seu.',description:state.search?'Tente pesquisar outro nome ou remova a busca.':'Crie um grupo, chame sua turma e comece a conversar.',actions:[state.search?{label:'Limpar busca',run:()=>{state.search='';$('group-search').value='';renderGroups();renderPublic();}}:{label:'Criar grupo',run:()=>$('new-group-button').click()}]}));
  $('public-groups').replaceChildren(fragment);
}
async function selectGroup(group) {
  resolutions.reset();
  chats.openGroup(group);
  const changed=state.group?.id!==group.id;state.mode='group';state.group=group;state.serial++;
  if(changed){state.bets=[];state.betsError=null;state.selected=null;state.detailKey='';state.listKey='';$('bets-list').replaceChildren(el('p','muted','Carregando apostas…'));show($('bet-detail'),false);$('ranking-list').replaceChildren(el('p','muted','Carregando ranking…'));$('ranking-summary').hidden=true;show($('ranking-error'),false);$('ranking-live-label').textContent='Carregando…';$('available-points').textContent='—';$('reserved-points').textContent='—';}
  sessionStorage.setItem('gamblr.group',group.id);renderGroups();$('explore-button').classList.remove('active');$('page-kicker').textContent='SEU GRUPO · PONTOS VIRTUAIS';$('page-title').textContent=group.name;groupSubtitle(group.memberCount);
  $('private-nav').classList.remove('active');show($('private-view'),false);show($('betting-column'));show($('empty-betting'),false);
  show($('explore-view'),false);show($('group-view'));show($('new-group-button'),false);show($('new-bet-button'));
  const cached=cachedBets(group.id);if(cached){state.bets=cached;renderBets();renderDetail();}
  await refreshGroup(true);
}
async function refreshGroup(force=false) {
  if(!state.group || (state.refreshing&&!force))return;state.refreshing=true;
  const id=state.group.id, serial=++state.serial;
  try{
    const [wallet,bets,ranking,settings]=await Promise.allSettled([api('writer',`/api/groups/${id}/wallet/me`),api('writer',`/api/groups/${id}/bets`),api('reader',`/api/groups/${id}/ranking`),api('writer',`/api/groups/${id}/settings`),participation.load(id)]);
    if(serial!==state.serial || state.group?.id!==id)return;
    if([wallet,bets].some(result=>result.status==='rejected'&&[403,404].includes(result.reason.status))){betCache.delete(id);state.bets=[];toast('Este grupo não está mais disponível para você.',true);await loadGroups();return;}
    if(settings.status==='fulfilled'){state.group.ownerId=settings.value.ownerId;state.group.name=settings.value.name;state.group.canInvite=settings.value.canInvite;$('page-title').textContent=settings.value.name;renderGroups();}
    if(wallet.status==='fulfilled'){$('available-points').textContent=points(wallet.value.availablePoints);$('reserved-points').textContent=points(wallet.value.reservedPoints);state.wallet=wallet.value;}
    if(bets.status==='fulfilled'){state.betsError=null;rememberBets(id,bets.value);state.bets=bets.value;if(!state.bets.some(card=>card.bet.id===state.selected))state.selected=state.bets[0]?.bet.id || null;renderBets();renderDetail();}
    else{state.betsError=bets.reason.message;renderBets();}
    if(ranking.status==='fulfilled'){state.ranking=ranking.value.ranking;renderRanking();show($('ranking-error'),false);$('ranking-live-label').textContent='Atualizado';}else{state.ranking=[];$('ranking-list').replaceChildren();$('ranking-summary').hidden=true;$('ranking-live-label').textContent='Indisponível';$('ranking-error').replaceChildren(failureState('Ranking indisponível.','Não foi possível consultar as posições. Tente atualizar os dados.',()=>refreshGroup(true)));show($('ranking-error'));}
    refreshOdds();resolutions.refresh();
  }finally{if(serial===state.serial)state.refreshing=false;}
}
function privateConversation(friend=null) {
  resolutions.reset();
  state.mode='private';state.group=null;state.serial++;state.refreshing=false;state.selected=null;state.bets=[];state.detailKey='';state.listKey='';renderGroups();
  $('explore-button').classList.remove('active');$('private-nav').classList.add('active');$('page-kicker').textContent='MENSAGENS';$('page-title').textContent='Conversas privadas';$('page-subtitle').textContent='Puxe um assunto com seus amigos.';
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
  const key=JSON.stringify([cards,state.selected,cards.map(card=>betStatus(card.bet)),state.betsError,state.group?.canInvite,state.group?.memberCount]);if(key===state.listKey)return;state.listKey=key;
  const fragment=document.createDocumentFragment();
  for(const card of cards){const bet=card.bet;const button=el('button',`bet-card situation-${betSituation(bet).key}`+(bet.id===state.selected?' selected':''));button.type='button';const top=el('div','card-topline');top.append(el('span','tag',bet.type==='BINARY'?'SIM OU NÃO':'MÚLTIPLA ESCOLHA'),statusTag(bet));button.append(top,el('h3','bet-question',bet.question),participationSlot(state.group.id,bet));const foot=el('div','card-foot');foot.append(el('span','',`Palpites até ${date(bet.predictionsCloseAt)}`),el('strong','',card.myPrediction?'✓ Você já palpitou':bet.authorId===state.profile.id?'Sua aposta':'Ver aposta ↗'));button.append(foot);button.onclick=()=>{state.selected=bet.id;state.listKey='';renderBets();renderDetail();refreshOdds();};fragment.append(button);}
  if(state.betsError)fragment.append(failureState('Não foi possível atualizar as apostas.',state.bets.length?'As apostas exibidas podem estar desatualizadas.':state.betsError,()=>refreshGroup(true)));
  else if(!cards.length){const actions=state.bets.length?[{label:'Limpar filtro',run:()=>{$('bet-filter').value='all';$('bet-filter').dispatchEvent(new Event('change'));}}]:[{label:'Publique a primeira aposta',run:()=>$('new-bet-button').click()}];if(!state.bets.length&&state.group?.memberCount===1&&state.group.canInvite)actions.push(inviteAction());fragment.append(emptyState({kind:state.bets.length?'search':'bets',title:state.bets.length?'Nenhuma aposta neste filtro.':'O grupo está esperando uma boa pergunta.',description:state.bets.length?'Experimente visualizar todas as apostas.':'Uma boa pergunta já dá assunto para a turma.',actions}));}
  $('bets-list').replaceChildren(fragment);
}
function renderDetail() {
  const card=state.bets.find(card=>card.bet.id===state.selected);show($('bet-detail'),!!card);if(!card){resolutions.reset();return;}
  const bet=card.bet, key=JSON.stringify([bet.id,bet.state,open(bet),card.myPrediction,state.group?.ownerId]);if(key===state.detailKey)return;state.detailKey=key;
  const detail=$('bet-detail');detail.replaceChildren();detail.append(el('span','eyebrow','ESCOLHA SEU LADO'),el('h3','',bet.question),el('p','detail-meta',`Mínimo de ${points(bet.minimumPoints)} pontos · Término ${date(bet.endsAt)}`));
  if(bet.verificationCriterion)detail.append(el('p','criterion',`Verificação: ${bet.verificationCriterion}`));
  const form=el('form');form.id='prediction-form';const options=el('div','option-grid');
  for(const option of bet.options){const label=el('label','option');const input=el('input');input.type='radio';input.name='option';input.value=option.id;input.required=true;input.disabled=!!card.myPrediction || bet.authorId===state.profile.id || !open(bet);if(card.myPrediction?.optionId===option.id)input.checked=true;
    label.append(input,el('span','option-name',option.label));const data=el('span','option-data'),odd=el('span','option-odd','—'),votes=el('span','option-votes','carregando');odd.dataset.odd=option.id;votes.dataset.votes=option.id;data.append(odd,votes);label.append(data);options.append(label);}
  const distribution=el('div','odds-distribution');distribution.setAttribute('role','img');distribution.setAttribute('aria-label','Distribuição dos votos: aguardando dados');
  const legend=el('div','odds-legend');
  bet.options.forEach((option,index)=>{const color=`hsl(${(index*137.508+215)%360} 65% 42%)`;const segment=el('span','odds-segment');segment.dataset.share=option.id;segment.style.backgroundColor=color;distribution.append(segment);const item=el('span','odds-legend-item'),swatch=el('span','odds-swatch');swatch.style.backgroundColor=color;item.append(swatch,document.createTextNode(option.label));legend.append(item);});
  form.append(options,distribution,legend);
  if(card.myPrediction){const option=bet.options.find(option=>option.id===card.myPrediction.optionId);form.append(el('div','confirmed-box',`✓ Seu palpite: ${option?.label || 'confirmado'} · ${points(card.myPrediction.points)} pontos reservados. A escolha e o valor são definitivos.`));}
  else if(bet.authorId===state.profile.id)form.append(el('p','prediction-locked','Você publicou esta aposta. Acompanhe os palpites do grupo; o autor não pode participar.'));
  else if(!open(bet))form.append(el('p','prediction-locked','O prazo para confirmar palpites já terminou.'));
  else{const entry=el('div','prediction-entry'),label=el('label','','Quanto você quer reservar?'),input=el('input');input.type='number';input.name='points';input.min=String(bet.minimumPoints);input.step='any';input.value=String(bet.minimumPoints);input.required=true;label.append(input);const button=el('button','button primary','Confirmar palpite ↗');button.type='submit';entry.append(label,button);form.append(entry,el('p','prediction-note','Ao confirmar, os pontos ficam reservados. O palpite não pode ser alterado ou retirado.'));}
  const error=el('p','form-error');error.role='alert';error.hidden=true;form.append(error);
  form.onsubmit=event=>{event.preventDefault();submit(form,async()=>{const values=new FormData(form);if(!values.get('option'))throw new ClientError('Escolha uma opção.');const result=await api('writer',`/api/bets/${bet.id}/predictions`,{method:'POST',body:{optionId:values.get('option'),points:values.get('points')}});card.myPrediction=result;state.detailKey='';renderDetail();toast('Palpite confirmado. Seus pontos foram reservados.');await refreshGroup(true);});};
  if(!active(bet)&&card.myPrediction){const confirmed=form.querySelector('.confirmed-box');if(confirmed)confirmed.textContent=`Seu palpite: ${bet.options.find(option=>option.id===card.myPrediction.optionId)?.label||'confirmado'} · ${points(card.myPrediction.points)} pontos. A reserva foi concluída.`;}
  detail.append(form);resolutions.attach(bet,detail);
  if(state.group?.ownerId===state.profile.id){const remove=el('button','button secondary delete-bet','Apagar aposta');remove.id='delete-bet-button';remove.type='button';const note=el('p','prediction-note',active(bet)?'Apagar cancela esta aposta e devolve todas as reservas.':'Apagar oculta esta aposta e preserva os pagamentos já concluídos.');detail.append(note,remove);remove.onclick=async()=>{remove.disabled=true;try{await api('writer',`/api/bets/${bet.id}/delete`,{method:'POST'});state.selected=null;state.detailKey='';await refreshGroup(true);chats.refresh();toast('Aposta apagada.');}catch(error){toast(error.message,true);remove.disabled=false;}};}
}
let oddsVersion = null, oddsRequest = null;
async function refreshOdds(live=false) {
  const id=state.selected;if(!id || state.mode!=='group')return;
  if(oddsRequest){if(live&&oddsRequest.id===id)return;oddsRequest.controller.abort();}
  const pending={id,controller:new AbortController()};oddsRequest=pending;
  try{const data=await api('reader',`/api/bets/${id}/odds`,{signal:pending.controller.signal,headers:live&&oddsVersion?.id===id?{'X-Odds-Version':oddsVersion.version}:{}});if(oddsRequest!==pending||state.selected!==id||state.mode!=='group')return;oddsVersion={id,version:data.version};for(const option of data.options){const odd=document.querySelector(`[data-odd="${option.optionId}"]`),votes=document.querySelector(`[data-votes="${option.optionId}"]`);const bar=document.querySelector(`[data-share="${option.optionId}"]`);if(bar){const total=Number(data.totalVotes),share=total>0?Number(option.votes)/total:0;bar.style.flexGrow=String(share);bar.title=`${option.label}: ${option.votes} voto(s), ${(share*100).toFixed(1)}% das pessoas`;bar.textContent=share>=0.08?`${(share*100).toFixed(0)}%`:''; }if(odd)odd.textContent=option.decimalOdds===null?'—':String(option.decimalOdds).replace('.',',');if(votes)votes.textContent=`${option.votes} voto${option.votes===1?'':'s'}`;}const distribution=document.querySelector('.odds-distribution');if(distribution)distribution.setAttribute('aria-label',Array.from(distribution.children).map(segment=>segment.title).join('; '));}
  catch(error){if(error.name!=='AbortError'&&state.selected===id)document.querySelectorAll('[data-votes]').forEach(node=>node.textContent='odds indisponíveis');}
  finally{if(oddsRequest===pending)oddsRequest=null;}
}
async function followOdds(){while(true){if(auth?.session&&state.profile&&!document.hidden&&state.mode==='group'&&state.selected)await refreshOdds(true);await new Promise(resolve=>setTimeout(resolve,500));}}
followOdds();
function renderRanking() {
  groupSubtitle(state.ranking.length);
  if(state.group)state.group.memberCount=state.ranking.length;
  $('ranking-summary').hidden=!state.ranking.length;$('ranking-summary').textContent=state.ranking.length?rankingMessage(state.ranking,state.profile.id):'';
  const fragment=document.createDocumentFragment();for(const row of state.ranking){const podium=Number(row.position)>=1&&Number(row.position)<=3,item=el('li','rank-row'+(podium?` podium podium-${row.position}`:'')+(row.userId===state.profile.id?' me':'')),position=el('span','rank-position',podium?`${row.position}º`:String(row.position).padStart(2,'0'));position.setAttribute('aria-label',`${row.position}º lugar`);item.append(position,el('span','avatar',row.displayName.slice(0,1).toUpperCase()),groupName(row,{className:'rank-name',suffix:row.userId===state.profile.id?' (você)':''}),el('strong','rank-points',points(row.availablePoints)));fragment.append(item);}
  if(!state.ranking.length){const row=el('li');row.append(emptyState({kind:'ranking',title:'Sem posições para exibir.',description:'As posições dos integrantes aparecerão aqui.',actions:[{label:'Atualizar ranking',run:()=>refreshGroup(true)}]}));fragment.append(row);}$('ranking-list').replaceChildren(fragment);
}
function groupSubtitle(count) {if(state.group&&state.profile)$('page-subtitle').textContent=`${count} integrante${count===1?'':'s'} · ${state.group.ownerId===state.profile.id?'Você é dono do grupo':'Conversa e palpites da turma'}`;}
async function checkStatus() {try{const status=await fetch('/status').then(r=>r.json());const up=status.writer&&status.reader;$('connection-label').classList.toggle('online',up);$('connection-label').querySelector('span').textContent=up?'Tudo conectado':'Conexão parcial';$('service-notice').textContent=!status.writer?'O serviço de apostas está indisponível. Inicie o Spring Boot para continuar.':!status.reader?'O serviço de ranking e odds está indisponível. Inicie o Flask para acompanhar o placar.':'';show($('service-notice'),!up);}catch{}}
function authMode(signup) {state.signup=signup;show($('name-label'),signup);$('auth-name').required=signup;$('auth-password').autocomplete=signup?'new-password':'current-password';$('login-tab').classList.toggle('active',!signup);$('signup-tab').classList.toggle('active',signup);$('auth-title').textContent=signup?'Seu grupo te espera.':'Entre na conversa.';$('auth-description').textContent=signup?'Crie sua conta e faça parte da disputa.':'Entre para ver as conversas e os palpites da sua turma.';$('auth-submit').textContent=signup?'Criar minha conta ↗':'Entrar no Gamblr ↗';show($('auth-error'),false);show($('auth-info'),false);}
$('login-tab').onclick=()=>authMode(false);$('signup-tab').onclick=()=>authMode(true);
$('auth-form').onsubmit=event=>{event.preventDefault();submit(event.currentTarget,async()=>{const email=$('auth-email').value.trim(),password=$('auth-password').value;if(state.signup){const data=await auth.signUp(email,password,$('auth-name').value.trim());if(!data.access_token){$('auth-password').value='';authMode(false);$('auth-info').textContent='Cadastro solicitado. Confira seu e-mail para confirmar a conta antes de entrar.';show($('auth-info'));return;}}else await auth.signIn(email,password);await enter();});};
$('logout-button').onclick=async()=>{try{await auth.logout();}catch{toast('Você saiu desta sessão. O serviço de autenticação não confirmou o encerramento remoto.',true);}};
$('feed-filters').onsubmit=event=>event.preventDefault();
$('feed-filters').oninput=()=>renderFeed();
$('feed-filters').onreset=()=>setTimeout(()=>{ $('feed-groups').querySelectorAll('input').forEach(input=>input.checked=false);renderFeed();},0);
$('feed-button').onclick=()=>openFeed().catch(error=>toast(error.message,true));
$('explore-button').onclick=()=>explore().catch(error=>toast(error.message,true));
$('home-button').onclick=$('explore-button').onclick;
$('messages-button').onclick=()=>privateConversation();$('private-nav').onclick=()=>privateConversation();
$('group-search').oninput=()=>{state.search=$('group-search').value.trim();renderGroups();if(state.mode==='explore')renderPublic();};
$('search-form').onsubmit=event=>{event.preventDefault();explore().catch(error=>toast(error.message,true));};
$('refresh-button').onclick=()=>{checkStatus();chats.refresh();if(state.mode==='group')refreshGroup(true).catch(error=>toast(error.message,true));else if(state.mode==='explore')loadPublic().catch(error=>toast(error.message,true));else if(state.mode==='feed')loadFeed();};
$('bet-filter').onchange=()=>{const filter=$('bet-filter').value,cards=state.bets.filter(card=>filter==='open'?active(card.bet):filter==='mine'?!!card.myPrediction:true);if(!cards.some(card=>card.bet.id===state.selected))state.selected=cards[0]?.bet.id||null;state.listKey='';state.detailKey='';renderBets();renderDetail();refreshOdds();};
function syncGroupEntry(){const privateGroup=$('group-visibility').value==='PRIVATE';if(privateGroup)$('group-entry').value='INVITE';$('group-entry').disabled=privateGroup;}
$('group-visibility').onchange=syncGroupEntry;
for(const id of ['new-group-button','new-group-side'])$(id).onclick=()=>{$('group-form').reset();syncGroupEntry();show($('group-form').querySelector('.form-error'),false);$('group-dialog').showModal();};
document.querySelectorAll('.close-dialog').forEach(button=>button.onclick=()=>button.closest('dialog').close());
$('new-bet-button').onclick=()=>{$('bet-form').reset();show($('options-label'),false);$('options-label').querySelector('textarea').required=false;show($('bet-form').querySelector('.form-error'),false);const end=new Date(Date.now()+3600000);end.setMinutes(end.getMinutes()-end.getTimezoneOffset());$('bet-ends').value=end.toISOString().slice(0,16);$('bet-dialog').showModal();};
$('bet-type').onchange=()=>{const multiple=$('bet-type').value==='MULTIPLE_CHOICE';show($('options-label'),multiple);$('options-label').querySelector('textarea').required=multiple;};
$('group-form').onsubmit=event=>{event.preventDefault();submit(event.currentTarget,async()=>{const data=new FormData(event.currentTarget);const group=await api('writer','/api/groups',{method:'POST',body:{name:data.get('name'),initialPoints:data.get('initialPoints'),dailyPoints:data.get('dailyPoints'),settlementSeconds:Number(data.get('settlementMinutes'))*60,contestSeconds:Number(data.get('contestMinutes'))*60,visibility:data.get('visibility'),entryMode:$('group-entry').value,invitePolicy:data.get('invitePolicy')}});$('group-dialog').close();await loadGroups(group.id);toast('Grupo criado. Use Gerenciar grupo para convidar pessoas e ajustar as regras.');});};
$('bet-form').onsubmit=event=>{event.preventDefault();submit(event.currentTarget,async()=>{const data=new FormData(event.currentTarget);const ends=new Date(data.get('endsAt'));if(!Number.isFinite(ends.getTime())||ends.getTime()<=Date.now())throw new ClientError('Escolha um término no futuro.');const options=data.get('type')==='MULTIPLE_CHOICE'?String(data.get('options')).split('\n').map(s=>s.trim()).filter(Boolean):null;const bet=await api('writer',`/api/groups/${state.group.id}/bets`,{method:'POST',body:{question:data.get('question'),type:data.get('type'),options,endsAt:ends.toISOString(),minimumPoints:data.get('minimumPoints'),verificationCriterion:data.get('verificationCriterion')||null}});state.selected=bet.id;$('bet-dialog').close();await refreshGroup(true);toast('Aposta publicada. Os dados já estão bloqueados para edição.');});};
async function start() {
  try{const params=new URLSearchParams(location.search);if(params.has('nova')){sessionStorage.removeItem('gamblr.session');sessionStorage.removeItem('gamblr.group');history.replaceState(null,'','/');}const config=await fetch('/config').then(r=>{if(!r.ok)throw new Error();return r.json();});auth=new AuthClient(config);$('auth-submit').disabled=false;auth.onChange=session=>{if(!session)signedOut();};if(auth.session)await enter();}
  catch(error){$('auth-error').textContent=error.message || 'Não foi possível carregar a configuração local.';show($('auth-error'));}
}
setInterval(()=>{if(!auth?.session || !state.profile || document.hidden)return;if(state.mode==='group')refreshGroup().catch(()=>{});else if(state.mode==='explore')loadPublic().catch(()=>{});else if(state.mode==='feed')loadFeed({background:true});},5000);
window.addEventListener('focus',()=>{if(auth?.session&&state.group)refreshGroup().catch(()=>{});});
initializeSidebars();
start();
