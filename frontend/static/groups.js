export function createGroupTools({api,profile,currentGroup,onChange}) {
  const $=id=>document.getElementById(id);
  const node=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;};
  let active=false,epoch=0,scope=null,inboxLoading=false;
  function error(message){$('manage-error').textContent=message;$('manage-error').hidden=false;}
  async function mutate(path,body,{leave=false}={}) {
    const session=epoch,group=currentGroup();if(!group)return;
    $('manage-error').hidden=true;
    try{await api('writer',path,{method:'POST',...(body===undefined?{}:{body})});if(session!==epoch)return;if(leave)$('manage-dialog').close();await onChange(leave?null:group.id);if(!leave&&$('manage-dialog').open)await load();}
    catch(problem){if(session===epoch)error(problem.message);}
  }
  function action(label,callback,cls='secondary') {const button=node('button','button '+cls,label);button.type='button';button.onclick=async()=>{button.disabled=true;try{await callback();}finally{button.disabled=false;}};return button;}
  function field(form,name,label,value,type='text') {
    const wrapper=node('label','',label),input=node('input');input.name=name;input.type=type;input.value=value;input.required=true;if(type==='number'){input.min='0';input.step='any';}wrapper.append(input);form.append(wrapper);return input;
  }
  function select(form,name,label,value,options) {
    const wrapper=node('label','',label),input=node('select');input.name=name;
    for(const [id,text] of options){const option=node('option','',text);option.value=id;input.append(option);}input.value=value;wrapper.append(input);form.append(wrapper);return input;
  }
  function settingsForm(settings) {
    const form=node('form');form.id='manage-settings-form';field(form,'name','Nome do grupo',settings.name);
    field(form,'initialPoints','Pontos iniciais para novos integrantes',settings.initialPoints,'number');field(form,'dailyPoints','Pontos diários',settings.dailyPoints,'number');
    form.append(node('p','muted','Crédito à meia-noite de Brasília. Alterações não modificam créditos passados ou apostas já publicadas.'));
    field(form,'settlementMinutes','Prazo de apuração (min)',settings.settlementSeconds/60,'number');field(form,'contestMinutes','Prazo de contestação (min)',settings.contestSeconds/60,'number');
    const visibility=select(form,'visibility','Visibilidade',settings.visibility,[['PUBLIC','Público'],['PRIVATE','Privado']]);
    const entry=select(form,'entryMode','Forma de entrada',settings.entryMode,[['IMMEDIATE','Entrada imediata'],['REQUEST','Solicitação de entrada'],['INVITE','Somente convite']]);
    select(form,'invitePolicy','Quem pode convidar',settings.invitePolicy,[['ALL_MEMBERS','Todos os integrantes'],['MODERATORS','Dono e moderadores']]);
    const sync=()=>{if(visibility.value==='PRIVATE')entry.value='INVITE';entry.disabled=visibility.value==='PRIVATE';};visibility.onchange=sync;sync();
    const submit=node('button','button primary','Salvar configurações');submit.type='submit';form.append(submit);
    form.onsubmit=async event=>{event.preventDefault();submit.disabled=true;try{const data=new FormData(form);await mutate(`/api/groups/${settings.id}/settings`,{name:data.get('name'),initialPoints:data.get('initialPoints'),dailyPoints:data.get('dailyPoints'),settlementSeconds:Math.round(Number(data.get('settlementMinutes'))*60),contestSeconds:Math.round(Number(data.get('contestMinutes'))*60),visibility:visibility.value,entryMode:entry.value,invitePolicy:data.get('invitePolicy')});}finally{submit.disabled=false;}};
    return form;
  }
  async function load() {
    const group=currentGroup();if(!group)return;const session=epoch,token={groupId:group.id};scope=token;$('manage-error').hidden=true;
    $('manage-role').textContent='Carregando administração…';$('manage-settings').replaceChildren();$('manage-members').replaceChildren();$('manage-requests').replaceChildren();$('manage-invite').hidden=true;
    try {
      const [settings,members]=await Promise.all([api('writer',`/api/groups/${group.id}/settings`),api('writer',`/api/groups/${group.id}/members`)]);
      if(session!==epoch||scope!==token||currentGroup()?.id!==group.id)return;
      $('manage-title').textContent=settings.name;$('manage-role').textContent={OWNER:'Você é dono do grupo',MODERATOR:'Você é moderador',MEMBER:'Você é integrante'}[settings.myRole];
      $('manage-settings').replaceChildren();if(settings.myRole==='OWNER')$('manage-settings').append(settingsForm(settings));
      const rows=document.createDocumentFragment();
      for(const member of members) {
        const row=node('div','member-row'),info=node('div');info.append(node('strong','',member.displayName),node('small','',{OWNER:'Dono',MODERATOR:'Moderador',MEMBER:'Integrante'}[member.role]));row.append(info);
        const buttons=node('div','member-actions');
        if(settings.myRole==='OWNER'&&member.role!=='OWNER') {
          buttons.append(action(member.role==='MODERATOR'?'Destituir moderador':'Promover a moderador',()=>mutate(`/api/groups/${group.id}/members/${member.userId}/role`,{role:member.role==='MODERATOR'?'MEMBER':'MODERATOR'})),action('Transferir propriedade',()=>mutate(`/api/groups/${group.id}/transfer`,{userId:member.userId})));
        }
        if(member.userId!==profile()?.id&&member.role!=='OWNER'&&(settings.myRole==='OWNER'||settings.myRole==='MODERATOR'&&member.role==='MEMBER'))buttons.append(action('Remover',()=>mutate(`/api/groups/${group.id}/members/${member.userId}/remove`)));
        row.append(buttons);rows.append(row);
      }
      $('manage-members').replaceChildren(rows);$('manage-invite').hidden=!settings.canInvite;$('invite-search-form').reset();$('invite-results').replaceChildren();
      $('manage-requests').replaceChildren();
      if(settings.myRole!=='MEMBER') {
        const requests=await api('writer',`/api/groups/${group.id}/join-requests`);if(scope!==token||session!==epoch)return;
        for(const request of requests){const row=node('div','member-row');row.append(node('strong','',request.displayName),action('Aprovar entrada',()=>mutate(`/api/groups/${group.id}/join-requests/${request.id}/approve`)),action('Recusar entrada',()=>mutate(`/api/groups/${group.id}/join-requests/${request.id}/decline`)));$('manage-requests').append(row);}
        if(!requests.length)$('manage-requests').append(node('p','muted','Nenhuma solicitação pendente.'));
      }
    }catch(problem){if(session===epoch&&scope===token)error(problem.message);}
  }
  async function inbox() {
    if(!active||inboxLoading)return;inboxLoading=true;const session=epoch;
    try {
      const invites=await api('writer','/api/group-invitations');if(session!==epoch)return;
      const rows=document.createDocumentFragment();
      for(const invite of invites) {
        const row=node('div','friend-request');row.append(node('strong','',invite.groupName),node('small','',`Convite de ${invite.senderName}`));
        for(const [verb,label] of [['accept','Aceitar convite'],['decline','Recusar convite']])row.append(action(label,async()=>{
          try{const result=await api('writer',`/api/group-invitations/${invite.id}/${verb}`,{method:'POST'});if(session!==epoch)return;await inbox();if(verb==='accept')await onChange(result.groupId);}
          catch(problem){if(session===epoch){$('invitations-error').textContent=problem.message;$('invitations-error').hidden=false;}}
        }));rows.append(row);
      }
      $('group-invitations').replaceChildren(rows);$('invitations-heading').hidden=!invites.length;$('invitations-error').hidden=true;
    }catch(problem){if(session===epoch){$('invitations-error').textContent=problem.message;$('invitations-error').hidden=false;}}
    finally{if(session===epoch)inboxLoading=false;}
  }
  $('manage-group-button').onclick=()=>{$('manage-dialog').showModal();load();};
  $('manage-refresh').onclick=load;
  $('leave-group-button').onclick=()=>{const group=currentGroup();if(group)mutate(`/api/groups/${group.id}/leave`,undefined,{leave:true});};
  $('invite-search-form').onsubmit=async event=>{
    event.preventDefault();const group=currentGroup(),token=scope,session=epoch;if(!group)return;const button=$('invite-search-submit');button.disabled=true;$('manage-error').hidden=true;
    try{const users=await api('writer',`/api/users?name=${encodeURIComponent($('invite-search').value.trim())}`);if(session!==epoch||scope!==token)return;
      const rows=document.createDocumentFragment();for(const user of users){const row=node('div','person-result');row.append(node('strong','',user.displayName),action('Convidar',async()=>{try{await api('writer',`/api/groups/${group.id}/invitations`,{method:'POST',body:{userId:user.id}});if(scope===token){row.replaceChildren(node('strong','',user.displayName),node('small','','Convite enviado'));}}catch(problem){if(scope===token)error(problem.message);}}));rows.append(row);}if(!users.length)rows.append(node('p','muted','Nenhum perfil encontrado.'));$('invite-results').replaceChildren(rows);
    }catch(problem){if(scope===token)error(problem.message);}finally{button.disabled=false;}
  };
  setInterval(()=>{if(active&&!document.hidden)inbox();},5000);
  return {start(){active=true;epoch++;inbox();},reset(){active=false;epoch++;scope=null;inboxLoading=false;for(const id of ['group-invitations','manage-settings','manage-members','manage-requests','invite-results'])$(id).replaceChildren();$('invitations-heading').hidden=true;$('manage-dialog').close();},inbox};
}
