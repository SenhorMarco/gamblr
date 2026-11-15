import {groupName} from './personalization.js';
import {emptyState} from './presentation.js';
import {continuesMessage} from './message-layout.js';
// Conversas e amizades usam somente as APIs autenticadas do Spring.
export function createChats({api,profile,onPrivate,onBet}) {
  const $=id=>document.getElementById(id);
  let active=false,epoch=0,scope=null,friends=[],friendsLoading=false;
  const node=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;};
  const visible=(id,value)=>{$(id).hidden=!value;};
  const uuid=()=>{const bytes=crypto.getRandomValues(new Uint8Array(16));bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;const s=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');return `${s.slice(0,8)}-${s.slice(8,12)}-${s.slice(12,16)}-${s.slice(16,20)}-${s.slice(20)}`;};
  function sidebar() {
    const list=$('friends-list'),fragment=document.createDocumentFragment();
    for(const friend of friends) {
      if(friend.status==='ACCEPTED') {
        const button=node('button','group-nav friend-nav'+(scope?.kind==='private'&&scope.id===friend.userId?' active':''));button.type='button';button.dataset.friend=friend.userId;
        button.append(node('span','avatar',friend.displayName.slice(0,1).toUpperCase()),node('span','',friend.displayName));button.onclick=()=>onPrivate(friend);fragment.append(button);
      } else {
        const row=node('div','friend-request');row.append(node('strong','',friend.displayName),node('small','',friend.incoming?'Quer ser seu amigo':'Pedido enviado'));
        if(friend.incoming)for(const [action,label] of [['accept','Aceitar'],['decline','Recusar']]) {
          const button=node('button','button secondary',label);button.type='button';
          button.onclick=async()=>{button.disabled=true;const session=epoch;try{await api('writer',`/api/friendships/${friend.id}/${action}`,{method:'POST'});if(session===epoch)await refreshFriends();}catch(error){if(session===epoch){$('friends-error').textContent=error.message;visible('friends-error',true);}}finally{button.disabled=false;}};row.append(button);
        }
        fragment.append(row);
      }
    }
    if(!friends.length)fragment.append(node('p','sidebar-empty','Nenhuma amizade ainda. Use + para encontrar alguém pelo nome.'));
    list.replaceChildren(fragment);
  }
  async function refreshFriends() {
    if(!active||friendsLoading)return;
    friendsLoading=true;const session=epoch;
    try{const result=await api('writer','/api/friendships');if(session!==epoch)return;friends=result;sidebar();visible('friends-error',false);}
    catch(error){if(session===epoch){$('friends-error').textContent=error.message;visible('friends-error',true);}}
    finally{if(session===epoch)friendsLoading=false;}
  }
  function pause() {scope=null;sidebar();}
  function open(kind,id) {
    scope={kind,id,before:null,nextBefore:null,key:'',loading:false,pending:null};
    const prefix=kind==='group'?'group':'private';
    $(prefix+'-thread').replaceChildren(node('p','chat-status','Carregando mensagens…'));
    $(prefix+'-message').value='';$(prefix+'-message').disabled=true;$(prefix+'-send').disabled=true;
    visible(prefix+'-error',false);visible(prefix+'-older',false);visible(prefix+'-latest',false);sidebar();refresh();
  }
  const path=s=>s.kind==='group'?`/api/groups/${s.id}/messages`:`/api/friends/${s.id}/messages`;
  async function refresh() {
    const current=scope;if(!active||!current||current.loading)return;current.loading=true;
    const prefix=current.kind==='group'?'group':'private',before=current.before;
    try {
      const page=await api('writer',path(current)+(before?`?before=${before}`:''));
      if(scope!==current||current.before!==before)return;
      current.nextBefore=page.nextBefore;
      const key=JSON.stringify(page);
      if(key!==current.key) {
        const thread=$(prefix+'-thread'),bottom=thread.scrollHeight-thread.scrollTop-thread.clientHeight<60||!current.key;const fragment=document.createDocumentFragment();
        for(const [messageIndex,message] of page.messages.entries()) {
          const row=node('article','chat-message'+(message.authorId===profile()?.id?' own':''));row.dataset.message=message.id;
          if(continuesMessage(page.messages[messageIndex-1],message))row.classList.add('message-continuation');
          const heading=node('div','message-heading'),time=node('time','',new Date(message.sentAt).toLocaleString('pt-BR',{day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'}));time.dateTime=message.sentAt;
          heading.append(current.kind==='group'?groupName(message,{tag:'strong'}):node('strong','',message.displayName),time);row.append(heading,node('p',message.deleted?'deleted-message':'',message.deleted?'Mensagem apagada pela moderação.':message.body));
          if(message.betId&&!message.deleted&&onBet){const button=node('button','button secondary','Ver aposta');button.type='button';button.onclick=()=>onBet(message.betId);row.append(button);}
          if(current.kind==='group'&&page.canModerate&&!message.deleted) {
            const remove=node('button','delete-message','Apagar');remove.type='button';remove.setAttribute('aria-label',`Apagar mensagem de ${message.displayName}`);
            remove.onclick=async()=>{remove.disabled=true;try{await api('writer',`${path(current)}/${message.id}/delete`,{method:'POST'});if(scope===current)await refresh();}catch(error){if(scope===current)chatError(prefix,error);}finally{remove.disabled=false;}};row.append(remove);
          }
          fragment.append(row);
        }
        if(!page.messages.length)fragment.append(emptyState({kind:'chat',title:before?'Nenhuma mensagem nesta página.':current.kind==='group'?'Nenhuma mensagem desde sua entrada.':'Nenhuma mensagem ainda. Diga olá!',description:before?'Volte para acompanhar as mensagens recentes.':current.kind==='group'?'Seu histórico começa na sua entrada. Que tal puxar um assunto?':'Pode começar com um olá. Esta conversa é só de vocês.',actions:[{label:before?'Voltar às recentes':'Escrever mensagem',run:()=>{if(before){current.before=null;current.key='';refresh();}else $(prefix+'-message').focus();}}]}));
        thread.replaceChildren(fragment);if(bottom)thread.scrollTop=thread.scrollHeight;current.key=key;
      }
      $(prefix+'-message').disabled=false;$(prefix+'-send').disabled=!!current.sending;
      visible(prefix+'-older',!!page.nextBefore);visible(prefix+'-latest',!!current.before);
      if(!current.sending&&!current.sendError)visible(prefix+'-error',false);
    } catch(error) {
      if(scope===current){chatError(prefix,error);if(error.status===403){$(prefix+'-message').disabled=true;$(prefix+'-send').disabled=true;$(prefix+'-thread').replaceChildren(emptyState({kind:'chat',title:'Conversa indisponível.',description:'Você não tem acesso a esta conversa.'}));current.key='';}else if(!current.key){const panel=emptyState({kind:'chat',title:'Não foi possível carregar as mensagens.',description:'A conversa está indisponível no momento.',actions:[{label:'Tentar novamente',run:()=>refresh()}]});panel.classList.add('load-failure');$(prefix+'-thread').replaceChildren(panel);}}
    } finally {current.loading=false;}
  }
  function chatError(prefix,error) {$(prefix+'-error').textContent=error.message;visible(prefix+'-error',true);}
  for(const prefix of ['group','private']) {
    $(prefix+'-chat-composer').onsubmit=async event=>{
      event.preventDefault();const current=scope;if(!current||current.sending||current.kind!==prefix)return;
      const input=$(prefix+'-message'),body=input.value.trim();if(!body)return;
      const pending=current.pending?.body===body?current.pending:{clientId:uuid(),body};current.pending=pending;current.sending=true;current.sendError=false;$(prefix+'-send').disabled=true;visible(prefix+'-error',false);
      try{await api('writer',path(current),{method:'POST',body:pending});if(scope===current){if(input.value.trim()===body)input.value='';current.pending=null;current.before=null;current.key='';await refresh();}}
      catch(error){if(scope===current){current.sendError=true;chatError(prefix,error);}}
      finally{current.sending=false;if(scope===current)$(prefix+'-send').disabled=input.disabled;}
    };
    $(prefix+'-message').onkeydown=event=>{if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();$(prefix+'-chat-composer').requestSubmit();}};
    $(prefix+'-older').onclick=()=>{if(scope&&!scope.loading){scope.before=scope.nextBefore;scope.key='';refresh();}};
    $(prefix+'-latest').onclick=()=>{if(scope&&!scope.loading){scope.before=null;scope.key='';refresh();}};
  }
  $('add-friend').onclick=()=>{$('friend-search-form').reset();$('friend-results').replaceChildren();visible('friend-search-error',false);$('friend-dialog').showModal();};
  $('friend-search-form').onsubmit=async event=>{
    event.preventDefault();const session=epoch,button=$('friend-search-submit');button.disabled=true;visible('friend-search-error',false);
    try {
      const people=await api('writer',`/api/users?name=${encodeURIComponent($('friend-search').value.trim())}`);if(session!==epoch)return;
      const fragment=document.createDocumentFragment();
      for(const person of people) {
        const row=node('div','person-result'),info=node('div');info.append(node('strong','',person.displayName),node('small','',`Código: ${person.id.slice(0,8)}`));row.append(info);
        const existing=friends.find(friend=>friend.userId===person.id),button=node('button','button secondary',existing?(existing.status==='ACCEPTED'?'Amigos':'Pedido pendente'):'Adicionar');button.type='button';button.disabled=!!existing;
        button.onclick=async()=>{button.disabled=true;try{await api('writer','/api/friendships',{method:'POST',body:{userId:person.id}});if(session!==epoch)return;button.textContent='Pedido enviado';await refreshFriends();}catch(error){if(session===epoch){button.disabled=false;$('friend-search-error').textContent=error.message;visible('friend-search-error',true);}}};row.append(button);fragment.append(row);
      }
      if(!people.length)fragment.append(node('p','muted','Nenhum usuário encontrado. A pessoa precisa entrar no Gamblr pelo menos uma vez.'));
      $('friend-results').replaceChildren(fragment);
    } catch(error){if(session===epoch){$('friend-search-error').textContent=error.message;visible('friend-search-error',true);}}
    finally{button.disabled=false;}
  };
  setInterval(()=>{if(active&&!document.hidden)refresh();},3000);
  setInterval(()=>{if(active&&!document.hidden)refreshFriends();},15000);
  window.addEventListener('focus',()=>{if(active){refresh();refreshFriends();}});
  return {
    start(){active=true;epoch++;refreshFriends();},
    reset(){active=false;epoch++;scope=null;friends=[];friendsLoading=false;sidebar();for(const prefix of ['group','private']){$(prefix+'-thread').replaceChildren();$(prefix+'-message').value='';$(prefix+'-message').disabled=true;$(prefix+'-send').disabled=true;}},
    openGroup(group){open('group',group.id);},
    openPrivate(friend){if(friend){$('private-chat-heading').textContent=friend.displayName;open('private',friend.userId);}else{pause();$('private-chat-heading').textContent='Mensagens privadas';$('private-thread').replaceChildren(emptyState({kind:'chat',title:'Uma conversa começa com um olá.',description:'Selecione um amigo à esquerda. O chat fica disponível após o aceite da amizade.',actions:[{label:'Encontrar pessoas',run:()=>$('add-friend').click()}]}));$('private-message').disabled=true;$('private-send').disabled=true;visible('private-error',false);visible('private-older',false);visible('private-latest',false);}},
    pause,refresh,
  };
}
