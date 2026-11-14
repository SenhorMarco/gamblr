import {test,expect} from '@playwright/test';
const uid=n=>`20000000-0000-0000-0000-${String(n).padStart(12,'0')}`;
test('group administration covers invitations requests hierarchy succession and deletion',async({browser},testInfo)=>{
  test.setTimeout(90000);
  const contexts=[],errors=[],people=[{id:uid(1),displayName:'Marco'},{id:uid(2),displayName:'Ana'},{id:uid(3),displayName:'Bia'}],members=new Map();
  let group=null,invite=null,entry=null,bet=null,prediction=null,deleted=false;const messages=[];
  const summary=id=>({...group,member:members.has(id),memberCount:members.size,requestPending:entry?.userId===id&&entry.state==='PENDING'});
  const settings=id=>({...group,myRole:id===group.ownerId?'OWNER':members.get(id),canInvite:id===group.ownerId||members.get(id)==='MODERATOR'||group.invitePolicy==='ALL_MEMBERS'});
  async function pageFor(index) {
    const person=people[index],context=await browser.newContext({viewport:{width:1440,height:1000}});contexts.push(context);
    await context.addInitScript(session=>sessionStorage.setItem('gamblr.session',JSON.stringify(session)),{access_token:person.id,refresh_token:person.id,expires_at:Math.floor(Date.now()/1000)+3600,user:{email:`user${index}@example.test`,user_metadata:{display_name:person.displayName}}});
    const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/status',route=>route.fulfill({json:{writer:true,reader:true}}));
    await page.route('**/reader/api/**',route=>route.fulfill({json:new URL(route.request().url()).pathname.endsWith('/ranking')?{ranking:[...members.keys()].map((id,i)=>({position:i+1,userId:id,displayName:people.find(p=>p.id===id).displayName,availablePoints:id===uid(3)&&prediction?70:100}))}:{options:[{optionId:uid(5),votes:prediction?1:0,decimalOdds:prediction?'1.00':null},{optionId:uid(6),votes:0,decimalOdds:null}]}}));
    await page.route('**/writer/api/**',async route=>{
      const request=route.request(),path=new URL(request.url()).pathname.replace('/writer',''),method=request.method(),body=method==='POST'?request.postDataJSON():null;let json={},status=200;
      if(path==='/api/me')json=person;
      else if(path==='/api/users')json=people.filter(p=>p.id!==person.id&&p.displayName.toLowerCase().includes(new URL(request.url()).searchParams.get('name').toLowerCase()));
      else if(path==='/api/friendships')json=[];
      else if(path==='/api/group-invitations')json=invite&&invite.userId===person.id&&invite.state==='PENDING'?[{id:uid(8),groupId:group.id,groupName:group.name,senderName:'Marco'}]:[];
      else if(path.includes('/group-invitations/')){invite.state=path.endsWith('/accept')?'ACCEPTED':'DECLINED';if(invite.state==='ACCEPTED')members.set(person.id,'MEMBER');json={groupId:group.id};}
      else if(path==='/api/groups'&&method==='POST'){group={...body,id:uid(4),ownerId:person.id};members.set(person.id,'MEMBER');json=summary(person.id);status=201;}
      else if(path==='/api/groups')json=group&&!deleted&&members.has(person.id)?[summary(person.id)]:[];
      else if(path==='/api/groups/public')json=group&&!deleted&&group.visibility==='PUBLIC'?[summary(person.id)]:[];
      else if(path.endsWith('/settings')){if(method==='POST')group={...group,...body};json=settings(person.id);}
      else if(path.endsWith('/members'))json=[...members].map(([id,role])=>({userId:id,displayName:people.find(p=>p.id===id).displayName,role:group.ownerId===id?'OWNER':role,firstJoinedAt:new Date().toISOString()}));
      else if(path.endsWith('/role'))members.set(path.split('/').at(-2),body.role);
      else if(path.endsWith('/remove'))members.delete(path.split('/').at(-2));
      else if(path.endsWith('/transfer')){members.set(group.ownerId,'MODERATOR');group.ownerId=body.userId;}
      else if(path.endsWith('/leave')){members.delete(person.id);if(person.id===group.ownerId){if(!members.size)deleted=true;else group.ownerId=[...members].sort((a,b)=>(b[1]==='MODERATOR')-(a[1]==='MODERATOR'))[0][0];}json={successorId:deleted?null:group.ownerId,groupDeleted:deleted};}
      else if(path.endsWith('/invitations')){invite={userId:body.userId,state:'PENDING'};json={id:uid(8)};}
      else if(path.endsWith('/join-requests')&&method==='POST'){entry={id:uid(9),userId:person.id,displayName:person.displayName,state:'PENDING'};json={id:uid(9)};}
      else if(path.endsWith('/join-requests'))json=entry?.state==='PENDING'?[entry]:[];
      else if(path.endsWith('/approve')){entry.state='APPROVED';members.set(entry.userId,'MEMBER');}
      else if(path.endsWith('/decline'))entry.state='DECLINED';
      else if(path.endsWith('/wallet/me'))json={availablePoints:person.id===uid(3)&&prediction?70:100,reservedPoints:person.id===uid(3)&&prediction?30:0};
      else if(path.endsWith('/messages'))json={messages,nextBefore:null,canModerate:person.id===group.ownerId||members.get(person.id)==='MODERATOR'};
      else if(path.endsWith('/bets')&&method==='POST'){
        bet={...body,id:uid(10),groupId:group.id,authorId:person.id,state:'OPEN',publishedAt:new Date().toISOString(),predictionsCloseAt:new Date(Date.now()+300000).toISOString(),authorCancelUntil:new Date(Date.now()+60000).toISOString(),options:[{id:uid(5),label:'Sim'},{id:uid(6),label:'Não'}]};
        messages.push({id:1,authorId:person.id,displayName:person.displayName,body:'Aposta: '+body.question,sentAt:new Date().toISOString(),deleted:false,betId:bet.id});json=bet;status=201;
      }
      else if(path.endsWith('/bets'))json=bet&&!bet.deleted?[{bet,myPrediction:person.id===uid(3)?prediction:null}]:[];
      else if(path.endsWith('/predictions')){prediction={id:uid(11),optionId:body.optionId,points:30};json=prediction;status=201;}
      else if(path.endsWith('/resolution'))json={state:bet.state,phase:'IN_PROGRESS',resultDeadline:new Date(Date.now()+3600000).toISOString(),pendingContests:0,myPendingContest:false,canCancel:person.id===bet.authorId,canPublishResult:false,canContest:false,canDecide:false};
      else if(path.endsWith('/delete')){bet.deleted=true;bet.state='CANCELLED';prediction=null;messages[0].deleted=true;messages[0].body=null;}
      else {status=404;json={code:'NOT_FOUND',message:'Rota não simulada'};}
      await route.fulfill({status,json});
    });
    await page.goto('/');await expect(page.locator('#app-view')).toBeVisible();return page;
  }
  try {
    const owner=await pageFor(0),ana=await pageFor(1),bia=await pageFor(2);
    await owner.locator('#new-group-button').click();await owner.getByLabel('Nome do grupo',{exact:true}).fill('Grupo privado');await owner.locator('#group-visibility').selectOption('PRIVATE');await expect(owner.locator('#group-entry')).toHaveValue('INVITE');await expect(owner.locator('#group-entry')).toBeDisabled();await owner.locator('#group-form [name=invitePolicy]').selectOption('MODERATORS');await owner.locator('#group-form button[type=submit]').click();await expect(owner.locator('#page-title')).toHaveText('Grupo privado');
    await owner.locator('#manage-group-button').click();await expect(owner.locator('#manage-role')).toHaveText('Você é dono do grupo');await owner.locator('#invite-search').fill('Ana');await owner.locator('#invite-search-submit').click();await owner.locator('#invite-results').getByRole('button',{name:'Convidar',exact:true}).click();await expect(owner.locator('#invite-results')).toContainText('Convite enviado');
    await expect(ana.locator('#group-invitations')).toContainText('Grupo privado',{timeout:10000});await ana.locator('#group-invitations').getByRole('button',{name:'Aceitar convite'}).click();await expect(ana.locator('#page-title')).toHaveText('Grupo privado');
    await owner.locator('#manage-refresh').click();const anaRow=owner.locator('#manage-members .member-row').filter({hasText:'Ana'});await anaRow.getByRole('button',{name:'Promover a moderador'}).click();await expect(anaRow).toContainText('Moderador');
    await owner.locator('#manage-settings-form [name=name]').fill('Grupo público');await owner.locator('#manage-settings-form [name=visibility]').selectOption('PUBLIC');await owner.locator('#manage-settings-form [name=entryMode]').selectOption('REQUEST');await owner.locator('#manage-settings-form [name=dailyPoints]').fill('5');await owner.locator('#manage-settings-form button[type=submit]').click();await expect(owner.locator('#page-title')).toHaveText('Grupo público');expect(group.dailyPoints).toBe('5');
    await bia.locator('#refresh-button').click();await bia.getByRole('button',{name:'Solicitar entrada',exact:true}).click();await expect(bia.locator('#public-groups')).toContainText('Solicitação enviada');
    await ana.locator('#manage-group-button').click();await expect(ana.locator('#manage-role')).toHaveText('Você é moderador');await expect(ana.locator('#manage-settings-form')).toHaveCount(0);await ana.locator('#manage-requests').getByRole('button',{name:'Aprovar entrada'}).click();await ana.keyboard.press('Escape');
    await bia.locator('#refresh-button').click();await bia.getByRole('button',{name:'Abrir grupo ↗'}).click();await expect(bia.locator('#page-title')).toHaveText('Grupo público');
    await owner.keyboard.press('Escape');await owner.locator('#new-bet-button').click();await owner.getByLabel('Pergunta',{exact:true}).fill('Aposta no histórico');await owner.locator('#bet-form button[type=submit]').click();await expect(ana.locator('#group-thread')).toContainText('Aposta no histórico',{timeout:10000});await ana.locator('#group-thread').getByRole('button',{name:'Ver aposta'}).click();await expect(ana.locator('#bet-detail')).toContainText('Aposta no histórico');await expect(ana.locator('#delete-bet-button')).toHaveCount(0);
    await bia.locator('#refresh-button').click();await bia.locator('#prediction-form input[type=radio]').first().check();await bia.getByLabel('Quanto você quer reservar?').fill('30');await bia.locator('#prediction-form button[type=submit]').click();await expect(bia.locator('#reserved-points')).toHaveText('30');await owner.locator('#delete-bet-button').click();await expect(owner.locator('#bets-list')).not.toContainText('Aposta no histórico');await bia.locator('#refresh-button').click();await expect(bia.locator('#available-points')).toHaveText('100');await expect(bia.locator('#reserved-points')).toHaveText('0');
    await owner.locator('#manage-group-button').click();await owner.locator('#manage-members .member-row').filter({hasText:'Ana'}).getByRole('button',{name:'Transferir propriedade'}).click();await expect(owner.locator('#manage-role')).toHaveText('Você é moderador');
    await ana.locator('#manage-group-button').click();await expect(ana.locator('#manage-role')).toHaveText('Você é dono do grupo');await ana.locator('#leave-group-button').click();await expect(ana.locator('#explore-view')).toBeVisible();await owner.locator('#manage-refresh').click();await expect(owner.locator('#manage-role')).toHaveText('Você é dono do grupo');
    await owner.locator('#manage-members .member-row').filter({hasText:'Bia'}).getByRole('button',{name:'Remover',exact:true}).click();await expect(owner.locator('#manage-members')).not.toContainText('Bia');await owner.screenshot({path:testInfo.outputPath('administration-desktop.png'),fullPage:true});await owner.setViewportSize({width:390,height:844});expect(await owner.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();await owner.screenshot({path:testInfo.outputPath('administration-mobile.png'),fullPage:true});await owner.locator('#leave-group-button').click();await expect(owner.locator('#no-groups')).toBeVisible();expect(deleted).toBeTruthy();expect(errors).toEqual([]);
  }finally{for(const context of contexts)await context.close();}
});
